/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.oap.server.storage.plugin.banyandb;

import com.google.gson.JsonObject;
import io.grpc.Status;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Singular;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.banyandb.v1.client.BanyanDBClient;
import org.apache.skywalking.banyandb.v1.client.grpc.exception.BanyanDBException;
import org.apache.skywalking.banyandb.v1.client.metadata.Catalog;
import org.apache.skywalking.banyandb.v1.client.metadata.Duration;
import org.apache.skywalking.banyandb.v1.client.metadata.Group;
import org.apache.skywalking.banyandb.v1.client.metadata.IndexRule;
import org.apache.skywalking.banyandb.v1.client.metadata.IntervalRule;
import org.apache.skywalking.banyandb.v1.client.metadata.Measure;
import org.apache.skywalking.banyandb.v1.client.metadata.NamedSchema;
import org.apache.skywalking.banyandb.v1.client.metadata.ResourceExist;
import org.apache.skywalking.banyandb.v1.client.metadata.Stream;
import org.apache.skywalking.banyandb.v1.client.metadata.TagFamilySpec;
import org.apache.skywalking.oap.server.core.analysis.DownSampling;
import org.apache.skywalking.oap.server.core.analysis.metrics.IntList;
import org.apache.skywalking.oap.server.core.config.ConfigService;
import org.apache.skywalking.oap.server.core.query.enumeration.Step;
import org.apache.skywalking.oap.server.core.storage.annotation.BanyanDB;
import org.apache.skywalking.oap.server.core.storage.annotation.ValueColumnMetadata;
import org.apache.skywalking.oap.server.core.storage.model.Model;
import org.apache.skywalking.oap.server.core.storage.model.ModelColumn;
import org.apache.skywalking.oap.server.core.storage.type.StorageDataComplexObject;

import javax.annotation.Nonnull;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public enum MetadataRegistry {
    INSTANCE;

    private final Map<String, Schema> registry = new HashMap<>();

    public Stream registerStreamModel(Model model, BanyanDBStorageConfig config, ConfigService configService) {
        final SchemaMetadata schemaMetadata = parseMetadata(model, config, configService);
        Schema.SchemaBuilder schemaBuilder = Schema.builder().metadata(schemaMetadata);
        Map<String, ModelColumn> modelColumnMap = model.getColumns().stream()
                .collect(Collectors.toMap(modelColumn -> modelColumn.getColumnName().getStorageName(), Function.identity()));
        // parse and set sharding keys
        List<String> entities = parseEntityNames(modelColumnMap);
        // parse tag metadata
        // this can be used to build both
        // 1) a list of TagFamilySpec,
        // 2) a list of IndexRule,
        List<TagMetadata> tags = parseTagMetadata(model, schemaBuilder);
        List<TagFamilySpec> tagFamilySpecs = schemaMetadata.extractTagFamilySpec(tags);
        // iterate over tagFamilySpecs to save tag names
        for (final TagFamilySpec tagFamilySpec : tagFamilySpecs) {
            for (final TagFamilySpec.TagSpec tagSpec : tagFamilySpec.tagSpecs()) {
                schemaBuilder.tag(tagSpec.getTagName());
            }
        }
        List<IndexRule> indexRules = tags.stream()
                .map(TagMetadata::getIndexRule)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        final Stream.Builder builder = Stream.create(schemaMetadata.getGroup(), schemaMetadata.name());
        if (entities.isEmpty()) {
            throw new IllegalStateException("sharding keys of model[stream." + model.getName() + "] must not be empty");
        }
        builder.setEntityRelativeTags(entities);
        builder.addTagFamilies(tagFamilySpecs);
        builder.addIndexes(indexRules);
        registry.put(schemaMetadata.name(), schemaBuilder.build());
        return builder.build();
    }

    public Measure registerMeasureModel(Model model, BanyanDBStorageConfig config, ConfigService configService) {
        final SchemaMetadata schemaMetadata = parseMetadata(model, config, configService);
        Schema.SchemaBuilder schemaBuilder = Schema.builder().metadata(schemaMetadata);
        Map<String, ModelColumn> modelColumnMap = model.getColumns().stream()
                .collect(Collectors.toMap(modelColumn -> modelColumn.getColumnName().getStorageName(), Function.identity()));
        // parse and set sharding keys
        List<String> entities = parseEntityNames(modelColumnMap);
        // parse tag metadata
        // this can be used to build both
        // 1) a list of TagFamilySpec,
        // 2) a list of IndexRule,
        List<TagMetadata> tags = parseTagMetadata(model, schemaBuilder);
        List<TagFamilySpec> tagFamilySpecs = schemaMetadata.extractTagFamilySpec(tags);
        // iterate over tagFamilySpecs to save tag names
        for (final TagFamilySpec tagFamilySpec : tagFamilySpecs) {
            for (final TagFamilySpec.TagSpec tagSpec : tagFamilySpec.tagSpecs()) {
                schemaBuilder.tag(tagSpec.getTagName());
            }
        }
        List<IndexRule> indexRules = tags.stream()
                .map(TagMetadata::getIndexRule)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        final Measure.Builder builder = Measure.create(schemaMetadata.getGroup(), schemaMetadata.name(),
                downSamplingDuration(model.getDownsampling()));
        if (entities.isEmpty()) { // if shardingKeys is empty, for measure, we can use ID as a single sharding key.
            builder.setEntityRelativeTags(Measure.ID);
        } else {
            builder.setEntityRelativeTags(entities);
        }
        builder.addTagFamilies(tagFamilySpecs);
        builder.addIndexes(indexRules);
        // parse and set field
        Optional<ValueColumnMetadata.ValueColumn> valueColumnOpt = ValueColumnMetadata.INSTANCE
                .readValueColumnDefinition(model.getName());
        valueColumnOpt.ifPresent(valueColumn -> builder.addField(parseFieldSpec(modelColumnMap.get(valueColumn.getValueCName()), valueColumn)));
        valueColumnOpt.ifPresent(valueColumn -> schemaBuilder.field(valueColumn.getValueCName()));
        registry.put(schemaMetadata.name(), schemaBuilder.build());
        return builder.build();
    }

    public Schema findMetadata(final Model model) {
        if (model.isRecord()) {
            return findRecordMetadata(model.getName());
        }
        return findMetadata(model.getName(), model.getDownsampling());
    }

    public Schema findRecordMetadata(final String recordModelName) {
        final Schema s = this.registry.get(recordModelName);
        if (s == null) {
            return null;
        }
        // impose sanity check
        if (s.getMetadata().getKind() != Kind.STREAM) {
            throw new IllegalArgumentException(recordModelName + "is not a record");
        }
        return s;
    }

    static DownSampling deriveFromStep(Step step) {
        switch (step) {
            case DAY:
                return DownSampling.Day;
            case HOUR:
                return DownSampling.Hour;
            case SECOND:
                return DownSampling.Second;
            default:
                return DownSampling.Minute;
        }
    }

    public Schema findMetadata(final String modelName, Step step) {
        return findMetadata(modelName, deriveFromStep(step));
    }

    /**
     * Find metadata with down-sampling
     */
    public Schema findMetadata(final String modelName, DownSampling downSampling) {
        return this.registry.get(SchemaMetadata.formatName(modelName, downSampling));
    }

    private Measure.FieldSpec parseFieldSpec(ModelColumn modelColumn, ValueColumnMetadata.ValueColumn valueColumn) {
        if (String.class.equals(modelColumn.getType())) {
            return Measure.FieldSpec.newIntField(valueColumn.getValueCName())
                    .compressWithZSTD()
                    .build();
        } else if (long.class.equals(modelColumn.getType()) || int.class.equals(modelColumn.getType())) {
            return Measure.FieldSpec.newIntField(valueColumn.getValueCName())
                    .compressWithZSTD()
                    .encodeWithGorilla()
                    .build();
        } else if (StorageDataComplexObject.class.isAssignableFrom(modelColumn.getType())) {
            return Measure.FieldSpec.newStringField(valueColumn.getValueCName())
                    .compressWithZSTD()
                    .build();
        } else if (double.class.equals(modelColumn.getType())) {
            // TODO: natively support double/float in BanyanDB
            log.warn("Double is stored as binary");
            return Measure.FieldSpec.newBinaryField(valueColumn.getValueCName())
                    .compressWithZSTD()
                    .build();
        } else {
            throw new UnsupportedOperationException(modelColumn.getType().getSimpleName() + " is not supported for field");
        }
    }

    Duration downSamplingDuration(DownSampling downSampling) {
        switch (downSampling) {
            case Hour:
                return Duration.ofHours(1);
            case Minute:
                return Duration.ofMinutes(1);
            case Day:
                return Duration.ofDays(1);
            default:
                throw new UnsupportedOperationException("unsupported downSampling interval");
        }
    }

    IndexRule parseIndexRule(String tagName, ModelColumn modelColumn) {
        // TODO: we need to add support index type in the OAP core
        // Currently, we only register INVERTED type
        // if it is null, it must be a user-defined tag
        if (modelColumn == null) {
            return IndexRule.create(tagName, IndexRule.IndexType.INVERTED, IndexRule.IndexLocation.SERIES);
        }
        if (modelColumn.getBanyanDBExtension().isGlobalIndexing()) {
            return IndexRule.create(tagName, parseIndexType(modelColumn.getBanyanDBExtension().getIndexType()), IndexRule.IndexLocation.GLOBAL);
        } else {
            return IndexRule.create(tagName, parseIndexType(modelColumn.getBanyanDBExtension().getIndexType()), IndexRule.IndexLocation.SERIES);
        }
    }

    private static IndexRule.IndexType parseIndexType(BanyanDB.IndexRule.IndexType indexType) {
        if (indexType == BanyanDB.IndexRule.IndexType.INVERTED) {
            return IndexRule.IndexType.INVERTED;
        }
        return IndexRule.IndexType.TREE;
    }

    /**
     * Parse sharding keys from the {@link Model}
     *
     * @param modelColumnMap the mapping between column storageName and {@link ModelColumn}
     * @return a list of column names in strict order
     */
    List<String> parseEntityNames(Map<String, ModelColumn> modelColumnMap) {
        List<ModelColumn> shardingColumns = new ArrayList<>();
        for (final ModelColumn col : modelColumnMap.values()) {
            if (col.getBanyanDBExtension().isShardingKey()) {
                shardingColumns.add(col);
            }
        }
        return shardingColumns.stream()
                .sorted(Comparator.comparingInt(col -> col.getBanyanDBExtension().getShardingKeyIdx()))
                .map(col -> col.getColumnName().getName())
                .collect(Collectors.toList());
    }

    List<TagMetadata> parseTagMetadata(Model model, Schema.SchemaBuilder builder) {
        List<TagMetadata> tagMetadataList = new ArrayList<>();
        // skip metric
        Optional<ValueColumnMetadata.ValueColumn> valueColumnOpt = ValueColumnMetadata.INSTANCE
                .readValueColumnDefinition(model.getName());
        for (final ModelColumn col : model.getColumns()) {
            if (valueColumnOpt.isPresent() && valueColumnOpt.get().getValueCName().equals(col.getColumnName().getStorageName())) {
                builder.spec(col.getColumnName().getStorageName(), new ColumnSpec(ColumnType.FIELD, col.getType()));
                continue;
            }
            final TagFamilySpec.TagSpec tagSpec = parseTagSpec(col);
            builder.spec(col.getColumnName().getStorageName(), new ColumnSpec(ColumnType.TAG, col.getType()));
            if (col.shouldIndex()) {
                // build indexRule
                IndexRule indexRule = parseIndexRule(tagSpec.getTagName(), col);
                tagMetadataList.add(new TagMetadata(indexRule, tagSpec));
            } else {
                tagMetadataList.add(new TagMetadata(null, tagSpec));
            }
        }

        return tagMetadataList;
    }

    /**
     * Parse TagSpec from {@link ModelColumn}
     *
     * @param modelColumn the column in the model to be parsed
     * @return a typed tag spec
     */
    @Nonnull
    private TagFamilySpec.TagSpec parseTagSpec(ModelColumn modelColumn) {
        final Class<?> clazz = modelColumn.getType();
        final String colName = modelColumn.getColumnName().getStorageName();
        TagFamilySpec.TagSpec tagSpec = null;
        if (String.class.equals(clazz) || StorageDataComplexObject.class.isAssignableFrom(clazz) || JsonObject.class.equals(clazz)) {
            tagSpec = TagFamilySpec.TagSpec.newStringTag(colName);
        } else if (int.class.equals(clazz) || long.class.equals(clazz)) {
            tagSpec = TagFamilySpec.TagSpec.newIntTag(colName);
        } else if (byte[].class.equals(clazz)) {
            tagSpec = TagFamilySpec.TagSpec.newBinaryTag(colName);
        } else if (clazz.isEnum()) {
            tagSpec = TagFamilySpec.TagSpec.newIntTag(colName);
        } else if (double.class.equals(clazz) || Double.class.equals(clazz)) {
            // serialize double as binary
            tagSpec = TagFamilySpec.TagSpec.newBinaryTag(colName);
        } else if (IntList.class.isAssignableFrom(clazz)) {
            tagSpec = TagFamilySpec.TagSpec.newIntArrayTag(colName);
        } else if (List.class.isAssignableFrom(clazz)) { // handle exceptions
            ParameterizedType t = (ParameterizedType) modelColumn.getGenericType();
            if (String.class.equals(t.getActualTypeArguments()[0])) {
                tagSpec = TagFamilySpec.TagSpec.newStringArrayTag(colName);
            }
        }
        if (tagSpec == null) {
            throw new IllegalStateException("type " + modelColumn.getType().toString() + " is not supported");
        }
        if (modelColumn.isIndexOnly()) {
            tagSpec.indexedOnly();
        }
        return tagSpec;
    }

    public SchemaMetadata parseMetadata(Model model, BanyanDBStorageConfig config, ConfigService configService) {
        if (model.isRecord()) {
            String group = "stream-default";
            if (model.isSuperDataset()) {
                // for superDataset, we should use separate group
                group = "stream-" + model.getName();
            }
            return new SchemaMetadata(group,
                    model.getName(),
                    Kind.STREAM,
                    model.getDownsampling(),
                    config.getRecordShardsNumber() *
                            (model.isSuperDataset() ? config.getSuperDatasetShardsFactor() : 1),
                    config.getStreamBlockInterval(),
                    config.getStreamSegmentInterval(),
                    configService.getRecordDataTTL()
            );
        }
        return new SchemaMetadata("measure-default", model.getName(), Kind.MEASURE,
                model.getDownsampling(),
                config.getMetricsShardsNumber(),
                config.getMeasureBlockInterval(),
                config.getMeasureSegmentInterval(),
                configService.getMetricsDataTTL());
    }

    @RequiredArgsConstructor
    @Data
    public static class SchemaMetadata {
        private final String group;
        /**
         * name of the {@link Model}
         */
        private final String modelName;
        private final Kind kind;
        /**
         * down-sampling of the {@link Model}
         */
        private final DownSampling downSampling;
        private final int shard;
        private final int blockIntervalHrs;
        private final int segmentIntervalHrs;
        private final int ttlDays;

        /**
         * Format the entity name for BanyanDB
         *
         * @param modelName    name of the model
         * @param downSampling not used if it is {@link DownSampling#None}
         * @return entity (e.g. measure, stream) name
         */
        static String formatName(String modelName, DownSampling downSampling) {
            if (downSampling == null || downSampling == DownSampling.None) {
                return modelName;
            }
            return modelName + "_" + downSampling.getName();
        }

        public Optional<NamedSchema<?>> findRemoteSchema(BanyanDBClient client) throws BanyanDBException {
            try {
                switch (kind) {
                    case STREAM:
                        return Optional.ofNullable(client.findStream(this.group, this.name()));
                    case MEASURE:
                        return Optional.ofNullable(client.findMeasure(this.group, this.name()));
                    default:
                        throw new IllegalStateException("should not reach here");
                }
            } catch (BanyanDBException ex) {
                if (ex.getStatus().equals(Status.Code.NOT_FOUND)) {
                    return Optional.empty();
                }

                throw ex;
            }
        }

        private List<TagFamilySpec> extractTagFamilySpec(List<TagMetadata> tagMetadataList) {
            Map<String, List<TagMetadata>> tagMetadataMap = tagMetadataList.stream()
                    .collect(Collectors.groupingBy(tagMetadata -> tagMetadata.isIndex() ? SchemaMetadata.this.indexFamily() : SchemaMetadata.this.nonIndexFamily()));

            final List<TagFamilySpec> tagFamilySpecs = new ArrayList<>(tagMetadataMap.size());
            for (final Map.Entry<String, List<TagMetadata>> entry : tagMetadataMap.entrySet()) {
                final TagFamilySpec.Builder b = TagFamilySpec.create(entry.getKey())
                        .addTagSpecs(entry.getValue().stream().map(TagMetadata::getTagSpec).collect(Collectors.toList()));
                if (this.getKind() == Kind.MEASURE && entry.getKey().equals(this.indexFamily())) {
                    // append measure ID, but it should not generate an index in the client side.
                    // BanyanDB will take care of the ID index registration.
                    b.addIDTagSpec();
                }
                tagFamilySpecs.add(b.build());
            }

            return tagFamilySpecs;
        }

        public boolean checkResourceExistence(BanyanDBClient client) throws BanyanDBException {
            ResourceExist resourceExist;
            switch (kind) {
                case STREAM:
                    resourceExist = client.existStream(this.group, this.name());
                    if (!resourceExist.hasGroup()) {
                        Group g = client.define(Group.create(this.group, Catalog.STREAM, this.shard,
                                IntervalRule.create(IntervalRule.Unit.HOUR, this.blockIntervalHrs),
                                IntervalRule.create(IntervalRule.Unit.HOUR, this.segmentIntervalHrs),
                                IntervalRule.create(IntervalRule.Unit.DAY, this.ttlDays)));
                        if (g != null) {
                            log.info("group {} created", g.name());
                        }
                    }
                    return resourceExist.hasResource();
                case MEASURE:
                    resourceExist = client.existMeasure(this.group, this.name());
                    if (!resourceExist.hasGroup()) {
                        Group g = client.define(Group.create(this.group, Catalog.MEASURE, this.shard,
                                IntervalRule.create(IntervalRule.Unit.HOUR, this.blockIntervalHrs),
                                IntervalRule.create(IntervalRule.Unit.HOUR, this.segmentIntervalHrs),
                                IntervalRule.create(IntervalRule.Unit.DAY, this.ttlDays)));
                        if (g != null) {
                            log.info("group {} created", g.name());
                        }
                    }
                    return resourceExist.hasResource();
                default:
                    throw new IllegalStateException("should not reach here");
            }
        }

        public String name() {
            if (this.kind == Kind.MEASURE) {
                return formatName(this.modelName, this.downSampling);
            }
            return this.modelName;
        }

        public String indexFamily() {
            switch (kind) {
                case MEASURE:
                    return "default";
                case STREAM:
                    return "searchable";
                default:
                    throw new IllegalStateException("should not reach here");
            }
        }

        public String nonIndexFamily() {
            switch (kind) {
                case MEASURE:
                case STREAM:
                    return "storage-only";
                default:
                    throw new IllegalStateException("should not reach here");
            }
        }
    }

    public enum Kind {
        MEASURE, STREAM;
    }

    @RequiredArgsConstructor
    @Getter
    private static class TagMetadata {
        private final IndexRule indexRule;
        private final TagFamilySpec.TagSpec tagSpec;

        boolean isIndex() {
            return this.indexRule != null;
        }
    }

    @Builder
    @EqualsAndHashCode
    public static class Schema {
        @Getter
        private final SchemaMetadata metadata;
        @Singular
        private final Map<String, ColumnSpec> specs;

        @Getter
        @Singular
        private final Set<String> tags;

        @Getter
        @Singular
        private final Set<String> fields;

        public ColumnSpec getSpec(String columnName) {
            return this.specs.get(columnName);
        }
    }

    @RequiredArgsConstructor
    @Getter
    public static class ColumnSpec {
        private final ColumnType columnType;
        private final Class<?> columnClass;
    }

    public enum ColumnType {
        TAG, FIELD;
    }
}
