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

import lombok.Getter;
import lombok.Setter;
import org.apache.skywalking.oap.server.library.module.ModuleConfig;

@Getter
@Setter
public class BanyanDBStorageConfig extends ModuleConfig {
    private String host = "127.0.0.1";
    private int port = 17912;
    /**
     * The maximum size of write entities in a single batch write call.
     */
    private int maxBulkSize = 5000;
    /**
     * Period of flush interval. In the timeunit of seconds.
     */
    private int flushInterval = 15;
    /**
     * Concurrent consumer threads for batch writing.
     */
    private int concurrentWriteThreads = 2;
    /**
     * Max size of {@link org.apache.skywalking.oap.server.core.query.type.ProfileTask} to be fetched
     * in a single request.
     */
    private int profileTaskQueryMaxSize;
    /**
     * Shards Number for measure/metrics.
     */
    private int metricsShardsNumber;
    /**
     * Shards Number for a normal record.
     */
    private int recordShardsNumber;
    /**
     * Shards Factor for a super dataset
     */
    private int superDatasetShardsFactor;
    /**
     * block interval for Stream group.
     * Unit is hours.
     *
     * @since 9.3.0
     */
    private int streamBlockInterval;
    /**
     * segment interval for Stream group.
     * Unit is hours.
     *
     * @since 9.3.0
     */
    private int streamSegmentInterval;
    /**
     * block interval for Measure group.
     * Unit is hours.
     *
     * @since 9.3.0
     */
    private int measureBlockInterval;
    /**
     * segment interval for Measure group.
     * Unit is hours.
     *
     * @since 9.3.0
     */
    private int measureSegmentInterval;
}
