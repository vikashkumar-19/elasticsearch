/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.engine;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.store.LockObtainFailedException;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.TestShardRouting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.core.internal.io.IOUtils;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.index.seqno.ReplicationTracker;
import org.elasticsearch.index.seqno.SequenceNumbers;
import org.elasticsearch.index.shard.DocsStats;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.index.translog.Translog;
import org.elasticsearch.test.IndexSettingsModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

public class NoOpEngineTests extends EngineTestCase {
    private static final IndexSettings INDEX_SETTINGS = IndexSettingsModule.newIndexSettings("index", Settings.EMPTY);

    public void testNoopEngine() throws IOException {
        engine.close();
        final NoOpEngine engine = new NoOpEngine(noOpConfig(INDEX_SETTINGS, store, primaryTranslogDir));
        assertThat(engine.refreshNeeded(), equalTo(false));
        assertThat(engine.shouldPeriodicallyFlush(), equalTo(false));
        engine.close();
    }

    public void testTwoNoopEngines() throws IOException {
        engine.close();
        // Ensure that we can't open two noop engines for the same store
        final EngineConfig engineConfig = noOpConfig(INDEX_SETTINGS, store, primaryTranslogDir);
        try (NoOpEngine ignored = new NoOpEngine(engineConfig)) {
            UncheckedIOException e = expectThrows(UncheckedIOException.class, () -> new NoOpEngine(engineConfig));
            assertThat(e.getCause(), instanceOf(LockObtainFailedException.class));
        }
    }

    public void testNoopAfterRegularEngine() throws IOException {
        int docs = randomIntBetween(1, 10);
        ReplicationTracker tracker = (ReplicationTracker) engine.config().getGlobalCheckpointSupplier();
        ShardRouting routing = TestShardRouting.newShardRouting("test", shardId.id(), "node",
            null, true, ShardRoutingState.STARTED, allocationId);
        IndexShardRoutingTable table = new IndexShardRoutingTable.Builder(shardId).addShard(routing).build();
        tracker.updateFromMaster(1L, Collections.singleton(allocationId.getId()), table);
        tracker.activatePrimaryMode(SequenceNumbers.NO_OPS_PERFORMED);
        for (int i = 0; i < docs; i++) {
            ParsedDocument doc = testParsedDocument("" + i, null, testDocumentWithTextField(), B_1, null);
            engine.index(indexForDoc(doc));
            tracker.updateLocalCheckpoint(allocationId.getId(), i);
        }

        engine.flush(true, true);

        long localCheckpoint = engine.getPersistedLocalCheckpoint();
        long maxSeqNo = engine.getSeqNoStats(100L).getMaxSeqNo();
        engine.close();

        final NoOpEngine noOpEngine = new NoOpEngine(noOpConfig(INDEX_SETTINGS, store, primaryTranslogDir, tracker));
        assertThat(noOpEngine.getPersistedLocalCheckpoint(), equalTo(localCheckpoint));
        assertThat(noOpEngine.getSeqNoStats(100L).getMaxSeqNo(), equalTo(maxSeqNo));
        try (Engine.IndexCommitRef ref = noOpEngine.acquireLastIndexCommit(false)) {
            try (IndexReader reader = DirectoryReader.open(ref.getIndexCommit())) {
                assertThat(reader.numDocs(), equalTo(docs));
            }
        }
        noOpEngine.close();
    }

    public void testNoOpEngineStats() throws Exception {
        IOUtils.close(engine, store);
        Settings.Builder settings = Settings.builder()
            .put(defaultSettings.getSettings())
            .put(IndexSettings.INDEX_SOFT_DELETES_RETENTION_OPERATIONS_SETTING.getKey(), 0);
        IndexSettings indexSettings = IndexSettingsModule.newIndexSettings(
            IndexMetaData.builder(defaultSettings.getIndexMetaData()).settings(settings).build());
        
        final AtomicLong globalCheckpoint = new AtomicLong(SequenceNumbers.NO_OPS_PERFORMED);
        try (Store store = createStore()) {
            Path translogPath = createTempDir();
            EngineConfig config = config(indexSettings, store, translogPath, NoMergePolicy.INSTANCE, null, null, globalCheckpoint::get);
            final int numDocs = scaledRandomIntBetween(10, 3000);
            int deletions = 0;
            try (InternalEngine engine = createEngine(config)) {
                for (int i = 0; i < numDocs; i++) {
                    engine.index(indexForDoc(createParsedDoc(Integer.toString(i), null)));
                    if (rarely()) {
                        engine.flush();
                    }
                    engine.syncTranslog(); // advance persisted local checkpoint
                    globalCheckpoint.set(engine.getPersistedLocalCheckpoint());
                }

                for (int i = 0; i < numDocs; i++) {
                    if (randomBoolean()) {
                        String delId = Integer.toString(i);
                        Engine.DeleteResult result = engine.delete(new Engine.Delete("_doc", delId, newUid(delId), primaryTerm.get()));
                        assertTrue(result.isFound());
                        engine.syncTranslog(); // advance persisted local checkpoint
                        globalCheckpoint.set(engine.getPersistedLocalCheckpoint());
                        deletions += 1;
                    }
                }
                engine.getLocalCheckpointTracker().waitForProcessedOpsToComplete(numDocs + deletions - 1);
                engine.flush(true, true);
            }

            final DocsStats expectedDocStats;
            boolean includeFileSize = randomBoolean();
            final SegmentsStats expectedSegmentStats;
            try (InternalEngine engine = createEngine(config)) {
                expectedDocStats = engine.docStats();
                expectedSegmentStats = engine.segmentsStats(includeFileSize, true);
            }

            try (NoOpEngine noOpEngine = new NoOpEngine(config)) {
                assertEquals(expectedDocStats.getCount(), noOpEngine.docStats().getCount());
                assertEquals(expectedDocStats.getDeleted(), noOpEngine.docStats().getDeleted());
                assertEquals(expectedDocStats.getTotalSizeInBytes(), noOpEngine.docStats().getTotalSizeInBytes());
                assertEquals(expectedDocStats.getAverageSizeInBytes(), noOpEngine.docStats().getAverageSizeInBytes());
                assertEquals(expectedSegmentStats.getCount(), noOpEngine.segmentsStats(includeFileSize, true).getCount());
                // don't compare memory in bytes since we load the index with term-dict off-heap
                assertEquals(expectedSegmentStats.getFileSizes().size(),
                    noOpEngine.segmentsStats(includeFileSize, true).getFileSizes().size());

                assertEquals(0, noOpEngine.segmentsStats(includeFileSize, false).getFileSizes().size());
                assertEquals(0, noOpEngine.segmentsStats(includeFileSize, false).getMemoryInBytes());
            } catch (AssertionError e) {
                logger.error(config.getMergePolicy());
                throw e;
            }
        }
    }

    public void testTrimUnreferencedTranslogFiles() throws Exception {
        final ReplicationTracker tracker = (ReplicationTracker) engine.config().getGlobalCheckpointSupplier();
        ShardRouting routing = TestShardRouting.newShardRouting("test", shardId.id(), "node",
            null, true, ShardRoutingState.STARTED, allocationId);
        IndexShardRoutingTable table = new IndexShardRoutingTable.Builder(shardId).addShard(routing).build();
        tracker.updateFromMaster(1L, Collections.singleton(allocationId.getId()), table);
        tracker.activatePrimaryMode(SequenceNumbers.NO_OPS_PERFORMED);
        engine.onSettingsChanged(TimeValue.MINUS_ONE, ByteSizeValue.ZERO, randomNonNegativeLong());
        final int numDocs = scaledRandomIntBetween(10, 3000);
        int totalTranslogOps = 0;
        for (int i = 0; i < numDocs; i++) {
            totalTranslogOps++;
            engine.index(indexForDoc(createParsedDoc(Integer.toString(i), null)));
            tracker.updateLocalCheckpoint(allocationId.getId(), i);
            if (rarely()) {
                totalTranslogOps = 0;
                engine.flush();
            }
            if (randomBoolean()) {
                engine.rollTranslogGeneration();
            }
        }
        // prevent translog from trimming so we can test trimUnreferencedFiles in NoOpEngine.
        final Translog.Snapshot snapshot = engine.getTranslog().newSnapshot();
        engine.flush(true, true);
        engine.close();

        final NoOpEngine noOpEngine = new NoOpEngine(noOpConfig(INDEX_SETTINGS, store, primaryTranslogDir, tracker));
        assertThat(noOpEngine.getTranslogStats().estimatedNumberOfOperations(), equalTo(totalTranslogOps));
        noOpEngine.trimUnreferencedTranslogFiles();
        assertThat(noOpEngine.getTranslogStats().estimatedNumberOfOperations(), equalTo(0));
        assertThat(noOpEngine.getTranslogStats().getUncommittedOperations(), equalTo(0));
        assertThat(noOpEngine.getTranslogStats().getTranslogSizeInBytes(), equalTo((long)Translog.DEFAULT_HEADER_SIZE_IN_BYTES));
        snapshot.close();
        noOpEngine.close();
    }
}
