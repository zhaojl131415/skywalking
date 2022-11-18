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

package org.apache.skywalking.oap.server.core.analysis.manual.trace;

import lombok.Getter;
import lombok.Setter;
import org.apache.skywalking.oap.server.core.Const;
import org.apache.skywalking.oap.server.core.analysis.Stream;
import org.apache.skywalking.oap.server.core.analysis.record.Record;
import org.apache.skywalking.oap.server.core.analysis.topn.TopN;
import org.apache.skywalking.oap.server.core.analysis.worker.RecordStreamProcessor;
import org.apache.skywalking.oap.server.core.source.ScopeDeclaration;
import org.apache.skywalking.oap.server.core.storage.annotation.BanyanDB;
import org.apache.skywalking.oap.server.core.storage.annotation.Column;
import org.apache.skywalking.oap.server.core.storage.type.Convert2Entity;
import org.apache.skywalking.oap.server.core.storage.type.Convert2Storage;
import org.apache.skywalking.oap.server.core.storage.type.StorageBuilder;

import static org.apache.skywalking.oap.server.core.source.DefaultScopeDefine.SAMPLED_SLOW_TRACE;

@Setter
@Getter
@ScopeDeclaration(id = SAMPLED_SLOW_TRACE, name = "SampledTraceSlowRecord")
@Stream(name = SampledSlowTraceRecord.INDEX_NAME, scopeId = SAMPLED_SLOW_TRACE, builder = SampledSlowTraceRecord.Builder.class, processor = RecordStreamProcessor.class)
public class SampledSlowTraceRecord extends Record {

    public static final String INDEX_NAME = "sampled_slow_trace_record";
    public static final String SCOPE = "scope";
    public static final String ENTITY_ID = "entity_id";
    public static final String TRACE_ID = TopN.TRACE_ID;
    public static final String URI = TopN.STATEMENT;
    public static final String LATENCY = "latency";

    @Column(columnName = SCOPE)
    private int scope;
    @Column(columnName = ENTITY_ID)
    private String entityId;
    @Column(columnName = TRACE_ID)
    @BanyanDB.ShardingKey(index = 0)
    private String traceId;
    @Column(columnName = URI, storageOnly = true)
    private String uri;
    @Column(columnName = LATENCY, dataType = Column.ValueDataType.SAMPLED_RECORD)
    private long latency;

    @Override
    public String id() {
        return getTimeBucket() + Const.ID_CONNECTOR + entityId + Const.ID_CONNECTOR + traceId;
    }

    public static class Builder implements StorageBuilder<SampledSlowTraceRecord> {

        @Override
        public SampledSlowTraceRecord storage2Entity(Convert2Entity converter) {
            final SampledSlowTraceRecord record = new SampledSlowTraceRecord();
            record.setScope(((Number) converter.get(SCOPE)).intValue());
            record.setEntityId((String) converter.get(ENTITY_ID));
            record.setTraceId((String) converter.get(TRACE_ID));
            record.setUri((String) converter.get(URI));
            record.setLatency(((Number) converter.get(LATENCY)).longValue());
            record.setTimeBucket(((Number) converter.get(TIME_BUCKET)).longValue());
            return record;
        }

        @Override
        public void entity2Storage(SampledSlowTraceRecord entity, Convert2Storage converter) {
            converter.accept(SCOPE, entity.getScope());
            converter.accept(ENTITY_ID, entity.getEntityId());
            converter.accept(TRACE_ID, entity.getTraceId());
            converter.accept(URI, entity.getUri());
            converter.accept(LATENCY, entity.getLatency());
            converter.accept(TIME_BUCKET, entity.getTimeBucket());
        }
    }
}