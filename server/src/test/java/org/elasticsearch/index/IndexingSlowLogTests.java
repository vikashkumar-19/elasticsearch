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

package org.elasticsearch.index;

import com.fasterxml.jackson.core.JsonParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.Term;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.logging.MockAppender;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.index.IndexingSlowLog.IndexingSlowLogMessage;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.engine.InternalEngineTests;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.index.mapper.SeqNoFieldMapper;
import org.elasticsearch.index.mapper.Uid;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.test.ESTestCase;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

public class IndexingSlowLogTests extends ESTestCase {
    static MockAppender appender;
    static Logger testLogger1 = LogManager.getLogger(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_PREFIX + ".index");

    @BeforeClass
    public static void init() throws IllegalAccessException {
        appender = new MockAppender("trace_appender");
        appender.start();
        Loggers.addAppender(testLogger1, appender);
    }

    @AfterClass
    public static void cleanup() {
        appender.stop();
        Loggers.removeAppender(testLogger1, appender);
    }


    public void testLevelPrecedence() {
        String uuid = UUIDs.randomBase64UUID();
        IndexMetaData metadata = createIndexMetadata(SlowLogLevel.WARN, "index-precedence", uuid);
        IndexSettings settings = new IndexSettings(metadata, Settings.EMPTY);
        IndexingSlowLog log = new IndexingSlowLog(settings);


        ParsedDocument doc = InternalEngineTests.createParsedDoc("1", null);
        Engine.Index index = new Engine.Index(new Term("_id", Uid.encodeId("doc_id")), randomNonNegativeLong(), doc);
        Engine.IndexResult result = Mockito.mock(Engine.IndexResult.class);//(0, 0, SequenceNumbers.UNASSIGNED_SEQ_NO, false);
        Mockito.when(result.getResultType()).thenReturn(Engine.Result.Type.SUCCESS);

        {
            //level set to WARN, should only log when WARN limit is breached
            Mockito.when(result.getTook()).thenReturn(40L);
            log.postIndex(ShardId.fromString("[index][123]"), index, result);
            assertNull(appender.getLastEventAndReset());

            Mockito.when(result.getTook()).thenReturn(41L);
            log.postIndex(ShardId.fromString("[index][123]"), index, result);
            assertNotNull(appender.getLastEventAndReset());

        }

        {
            // level set INFO, should log when INFO level is breached
            settings.updateIndexMetaData(createIndexMetadata(SlowLogLevel.INFO, "index", uuid));
            Mockito.when(result.getTook()).thenReturn(30L);
            log.postIndex(ShardId.fromString("[index][123]"), index, result);
            assertNull(appender.getLastEventAndReset());

            Mockito.when(result.getTook()).thenReturn(31L);
            log.postIndex(ShardId.fromString("[index][123]"), index, result);
            assertNotNull(appender.getLastEventAndReset());
        }

        {
            // level set DEBUG, should log when DEBUG level is breached
            settings.updateIndexMetaData(createIndexMetadata(SlowLogLevel.DEBUG, "index", uuid));
            Mockito.when(result.getTook()).thenReturn(20L);
            log.postIndex(ShardId.fromString("[index][123]"), index, result);
            assertNull(appender.getLastEventAndReset());

            Mockito.when(result.getTook()).thenReturn(21L);
            log.postIndex(ShardId.fromString("[index][123]"), index, result);
            assertNotNull(appender.getLastEventAndReset());
        }

        {
            // level set TRACE, should log when TRACE level is breached
            settings.updateIndexMetaData(createIndexMetadata(SlowLogLevel.TRACE, "index", uuid));
            Mockito.when(result.getTook()).thenReturn(10L);
            log.postIndex(ShardId.fromString("[index][123]"), index, result);
            assertNull(appender.getLastEventAndReset());

            Mockito.when(result.getTook()).thenReturn(11L);
            log.postIndex(ShardId.fromString("[index][123]"), index, result);
            assertNotNull(appender.getLastEventAndReset());
        }
    }

    public void testTwoLoggersDifferentLevel() {
        IndexSettings index1Settings = new IndexSettings(createIndexMetadata(SlowLogLevel.WARN, "index1", UUIDs.randomBase64UUID()),
            Settings.EMPTY);
        IndexingSlowLog log1 = new IndexingSlowLog(index1Settings);

        IndexSettings index2Settings = new IndexSettings(createIndexMetadata(SlowLogLevel.TRACE, "index2", UUIDs.randomBase64UUID()),
            Settings.EMPTY);
        IndexingSlowLog log2 = new IndexingSlowLog(index2Settings);


        ParsedDocument doc = InternalEngineTests.createParsedDoc("1", null);
        Engine.Index index = new Engine.Index(new Term("_id", Uid.encodeId("doc_id")), randomNonNegativeLong(), doc);
        Engine.IndexResult result = Mockito.mock(Engine.IndexResult.class);
        Mockito.when(result.getResultType()).thenReturn(Engine.Result.Type.SUCCESS);

        {
            // level set WARN, should not log
            Mockito.when(result.getTook()).thenReturn(11L);
            log1.postIndex(ShardId.fromString("[index][123]"), index, result);
            assertNull(appender.getLastEventAndReset());

            // level set TRACE, should log
            log2.postIndex(ShardId.fromString("[index][123]"), index, result);
            assertNotNull(appender.getLastEventAndReset());
        }
    }

    public void testMultipleSlowLoggersUseSingleLog4jLogger() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);

        IndexSettings index1Settings = new IndexSettings(createIndexMetadata(SlowLogLevel.WARN, "index1", UUIDs.randomBase64UUID()),
            Settings.EMPTY);
        IndexingSlowLog log1 = new IndexingSlowLog(index1Settings);

        int numberOfLoggersBefore = context.getLoggers().size();


        IndexSettings index2Settings = new IndexSettings(createIndexMetadata(SlowLogLevel.TRACE, "index2", UUIDs.randomBase64UUID()),
            Settings.EMPTY);
        IndexingSlowLog log2 = new IndexingSlowLog(index2Settings);
        context = (LoggerContext) LogManager.getContext(false);

        int numberOfLoggersAfter = context.getLoggers().size();
        assertThat(numberOfLoggersAfter, equalTo(numberOfLoggersBefore));
    }

    private IndexMetaData createIndexMetadata(SlowLogLevel level, String index, String uuid) {
        return newIndexMeta(index, Settings.builder()
            .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetaData.SETTING_INDEX_UUID, uuid)
            .put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_LEVEL_SETTING.getKey(), level)
            .put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_THRESHOLD_INDEX_TRACE_SETTING.getKey(), "10nanos")
            .put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_THRESHOLD_INDEX_DEBUG_SETTING.getKey(), "20nanos")
            .put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_THRESHOLD_INDEX_INFO_SETTING.getKey(), "30nanos")
            .put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_THRESHOLD_INDEX_WARN_SETTING.getKey(), "40nanos")
            .build());
    }

    public void testSlowLogMessageHasJsonFields() throws IOException {
        BytesReference source = BytesReference.bytes(JsonXContent.contentBuilder()
            .startObject().field("foo", "bar").endObject());
        ParsedDocument pd = new ParsedDocument(new NumericDocValuesField("version", 1),
            SeqNoFieldMapper.SequenceIDFields.emptySeqID(), "id",
            "test", "routingValue", null, source, XContentType.JSON, null);
        Index index = new Index("foo", "123");
        // Turning off document logging doesn't log source[]
        IndexingSlowLogMessage p = new IndexingSlowLogMessage(index, pd, 10, true, 0);

        assertThat(p.getValueFor("message"), equalTo("[foo/123]"));
        assertThat(p.getValueFor("took"), equalTo("10nanos"));
        assertThat(p.getValueFor("took_millis"), equalTo("0"));
        assertThat(p.getValueFor("doc_type"), equalTo("test"));
        assertThat(p.getValueFor("id"), equalTo("id"));
        assertThat(p.getValueFor("routing"), equalTo("routingValue"));
        assertThat(p.getValueFor("source"), is(emptyOrNullString()));

        // Turning on document logging logs the whole thing
        p = new IndexingSlowLogMessage(index, pd, 10, true, Integer.MAX_VALUE);
        assertThat(p.getValueFor("source"), containsString("{\\\"foo\\\":\\\"bar\\\"}"));
    }

    public void testSlowLogParsedDocumentPrinterSourceToLog() throws IOException {
        BytesReference source = BytesReference.bytes(JsonXContent.contentBuilder()
            .startObject().field("foo", "bar").endObject());
        ParsedDocument pd = new ParsedDocument(new NumericDocValuesField("version", 1),
            SeqNoFieldMapper.SequenceIDFields.emptySeqID(), "id",
            "test", null, null, source, XContentType.JSON, null);
        Index index = new Index("foo", "123");
        // Turning off document logging doesn't log source[]
        IndexingSlowLogMessage p = new IndexingSlowLogMessage(index, pd, 10, true, 0);
        assertThat(p.getFormattedMessage(), not(containsString("source[")));

        // Turning on document logging logs the whole thing
        p = new IndexingSlowLogMessage(index, pd, 10, true, Integer.MAX_VALUE);
        assertThat(p.getFormattedMessage(), containsString("source[{\"foo\":\"bar\"}]"));

        // And you can truncate the source
        p = new IndexingSlowLogMessage(index, pd, 10, true, 3);
        assertThat(p.getFormattedMessage(), containsString("source[{\"f]"));

        // And you can truncate the source
        p = new IndexingSlowLogMessage(index, pd, 10, true, 3);
        assertThat(p.getFormattedMessage(), containsString("source[{\"f]"));
        assertThat(p.getFormattedMessage(), startsWith("[foo/123] took"));

        // Throwing a error if source cannot be converted
        source = new BytesArray("invalid");
        ParsedDocument doc = new ParsedDocument(new NumericDocValuesField("version", 1),
            SeqNoFieldMapper.SequenceIDFields.emptySeqID(), "id",
            "test", null, null, source, XContentType.JSON, null);

        final UncheckedIOException e = expectThrows(UncheckedIOException.class,
            () -> new IndexingSlowLogMessage(index, doc, 10, true, 3));
        assertThat(e, hasToString(containsString("_failed_to_convert_[Unrecognized token 'invalid':"
            + " was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')\\n"
            + " at [Source: (org.elasticsearch.common.bytes.AbstractBytesReference$MarkSupportingStreamInputWrapper)")));
        assertNotNull(e.getCause());
        assertThat(e.getCause(), instanceOf(JsonParseException.class));
        assertThat(e.getCause(), hasToString(containsString("Unrecognized token 'invalid':"
            + " was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')\n"
            + " at [Source: (org.elasticsearch.common.bytes.AbstractBytesReference$MarkSupportingStreamInputWrapper)")));
    }

    public void testReformatSetting() {
        IndexMetaData metaData = newIndexMeta("index", Settings.builder()
            .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_REFORMAT_SETTING.getKey(), false)
            .build());
        IndexSettings settings = new IndexSettings(metaData, Settings.EMPTY);
        IndexingSlowLog log = new IndexingSlowLog(settings);
        assertFalse(log.isReformat());
        settings.updateIndexMetaData(newIndexMeta("index",
            Settings.builder().put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_REFORMAT_SETTING.getKey(), "true").build()));
        assertTrue(log.isReformat());

        settings.updateIndexMetaData(newIndexMeta("index",
            Settings.builder().put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_REFORMAT_SETTING.getKey(), "false").build()));
        assertFalse(log.isReformat());

        settings.updateIndexMetaData(newIndexMeta("index", Settings.EMPTY));
        assertTrue(log.isReformat());

        metaData = newIndexMeta("index", Settings.builder()
            .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .build());
        settings = new IndexSettings(metaData, Settings.EMPTY);
        log = new IndexingSlowLog(settings);
        assertTrue(log.isReformat());
        try {
            settings.updateIndexMetaData(newIndexMeta("index",
                Settings.builder().put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_REFORMAT_SETTING.getKey(), "NOT A BOOLEAN").build()));
            fail();
        } catch (IllegalArgumentException ex) {
            final String expected = "illegal value can't update [index.indexing.slowlog.reformat] from [true] to [NOT A BOOLEAN]";
            assertThat(ex, hasToString(containsString(expected)));
            assertNotNull(ex.getCause());
            assertThat(ex.getCause(), instanceOf(IllegalArgumentException.class));
            final IllegalArgumentException cause = (IllegalArgumentException) ex.getCause();
            assertThat(cause,
                hasToString(containsString("Failed to parse value [NOT A BOOLEAN] as only [true] or [false] are allowed.")));
        }
        assertTrue(log.isReformat());
    }

    public void testLevelSetting() {
        SlowLogLevel level = randomFrom(SlowLogLevel.values());
        IndexMetaData metaData = newIndexMeta("index", Settings.builder()
            .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_LEVEL_SETTING.getKey(), level)
            .build());
        IndexSettings settings = new IndexSettings(metaData, Settings.EMPTY);
        IndexingSlowLog log = new IndexingSlowLog(settings);
        assertEquals(level, log.getLevel());
        level = randomFrom(SlowLogLevel.values());
        settings.updateIndexMetaData(newIndexMeta("index",
            Settings.builder().put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_LEVEL_SETTING.getKey(), level).build()));
        assertEquals(level, log.getLevel());
        level = randomFrom(SlowLogLevel.values());
        settings.updateIndexMetaData(newIndexMeta("index",
            Settings.builder().put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_LEVEL_SETTING.getKey(), level).build()));
        assertEquals(level, log.getLevel());


        settings.updateIndexMetaData(newIndexMeta("index",
            Settings.builder().put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_LEVEL_SETTING.getKey(), level).build()));
        assertEquals(level, log.getLevel());

        settings.updateIndexMetaData(newIndexMeta("index", Settings.EMPTY));
        assertEquals(SlowLogLevel.TRACE, log.getLevel());

        metaData = newIndexMeta("index", Settings.builder()
            .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .build());
        settings = new IndexSettings(metaData, Settings.EMPTY);
        log = new IndexingSlowLog(settings);
        assertTrue(log.isReformat());
        try {
            settings.updateIndexMetaData(newIndexMeta("index",
                Settings.builder().put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_LEVEL_SETTING.getKey(), "NOT A LEVEL").build()));
            fail();
        } catch (IllegalArgumentException ex) {
            final String expected = "illegal value can't update [index.indexing.slowlog.level] from [TRACE] to [NOT A LEVEL]";
            assertThat(ex, hasToString(containsString(expected)));
            assertNotNull(ex.getCause());
            assertThat(ex.getCause(), instanceOf(IllegalArgumentException.class));
            final IllegalArgumentException cause = (IllegalArgumentException) ex.getCause();
            assertThat(cause, hasToString(containsString("No enum constant org.elasticsearch.index.SlowLogLevel.NOT A LEVEL")));
        }
        assertEquals(SlowLogLevel.TRACE, log.getLevel());

        metaData = newIndexMeta("index", Settings.builder()
            .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetaData.SETTING_INDEX_UUID, UUIDs.randomBase64UUID())
            .put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_LEVEL_SETTING.getKey(), SlowLogLevel.DEBUG)
            .build());
        settings = new IndexSettings(metaData, Settings.EMPTY);
        IndexingSlowLog debugLog = new IndexingSlowLog(settings);

        metaData = newIndexMeta("index", Settings.builder()
            .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetaData.SETTING_INDEX_UUID, UUIDs.randomBase64UUID())
            .put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_LEVEL_SETTING.getKey(), SlowLogLevel.INFO)
            .build());
        settings = new IndexSettings(metaData, Settings.EMPTY);
        IndexingSlowLog infoLog = new IndexingSlowLog(settings);

        assertEquals(SlowLogLevel.DEBUG, debugLog.getLevel());
        assertEquals(SlowLogLevel.INFO, infoLog.getLevel());
    }

    public void testSetLevels() {
        IndexMetaData metaData = newIndexMeta("index", Settings.builder()
            .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_THRESHOLD_INDEX_TRACE_SETTING.getKey(), "100ms")
            .put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_THRESHOLD_INDEX_DEBUG_SETTING.getKey(), "200ms")
            .put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_THRESHOLD_INDEX_INFO_SETTING.getKey(), "300ms")
            .put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_THRESHOLD_INDEX_WARN_SETTING.getKey(), "400ms")
            .build());
        IndexSettings settings = new IndexSettings(metaData, Settings.EMPTY);
        IndexingSlowLog log = new IndexingSlowLog(settings);
        assertEquals(TimeValue.timeValueMillis(100).nanos(), log.getIndexTraceThreshold());
        assertEquals(TimeValue.timeValueMillis(200).nanos(), log.getIndexDebugThreshold());
        assertEquals(TimeValue.timeValueMillis(300).nanos(), log.getIndexInfoThreshold());
        assertEquals(TimeValue.timeValueMillis(400).nanos(), log.getIndexWarnThreshold());

        settings.updateIndexMetaData(newIndexMeta("index",
            Settings.builder().put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_THRESHOLD_INDEX_TRACE_SETTING.getKey(), "120ms")
                .put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_THRESHOLD_INDEX_DEBUG_SETTING.getKey(), "220ms")
                .put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_THRESHOLD_INDEX_INFO_SETTING.getKey(), "320ms")
                .put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_THRESHOLD_INDEX_WARN_SETTING.getKey(), "420ms").build()));


        assertEquals(TimeValue.timeValueMillis(120).nanos(), log.getIndexTraceThreshold());
        assertEquals(TimeValue.timeValueMillis(220).nanos(), log.getIndexDebugThreshold());
        assertEquals(TimeValue.timeValueMillis(320).nanos(), log.getIndexInfoThreshold());
        assertEquals(TimeValue.timeValueMillis(420).nanos(), log.getIndexWarnThreshold());

        metaData = newIndexMeta("index", Settings.builder()
            .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .build());
        settings.updateIndexMetaData(metaData);
        assertEquals(TimeValue.timeValueMillis(-1).nanos(), log.getIndexTraceThreshold());
        assertEquals(TimeValue.timeValueMillis(-1).nanos(), log.getIndexDebugThreshold());
        assertEquals(TimeValue.timeValueMillis(-1).nanos(), log.getIndexInfoThreshold());
        assertEquals(TimeValue.timeValueMillis(-1).nanos(), log.getIndexWarnThreshold());

        settings = new IndexSettings(metaData, Settings.EMPTY);
        log = new IndexingSlowLog(settings);

        assertEquals(TimeValue.timeValueMillis(-1).nanos(), log.getIndexTraceThreshold());
        assertEquals(TimeValue.timeValueMillis(-1).nanos(), log.getIndexDebugThreshold());
        assertEquals(TimeValue.timeValueMillis(-1).nanos(), log.getIndexInfoThreshold());
        assertEquals(TimeValue.timeValueMillis(-1).nanos(), log.getIndexWarnThreshold());
        try {
            settings.updateIndexMetaData(newIndexMeta("index",
                Settings.builder().put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_THRESHOLD_INDEX_TRACE_SETTING.getKey(), "NOT A TIME VALUE")
                    .build()));
            fail();
        } catch (IllegalArgumentException ex) {
            assertTimeValueException(ex, "index.indexing.slowlog.threshold.index.trace");
        }

        try {
            settings.updateIndexMetaData(newIndexMeta("index",
                Settings.builder().put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_THRESHOLD_INDEX_DEBUG_SETTING.getKey(), "NOT A TIME VALUE")
                    .build()));
            fail();
        } catch (IllegalArgumentException ex) {
            assertTimeValueException(ex, "index.indexing.slowlog.threshold.index.debug");
        }

        try {
            settings.updateIndexMetaData(newIndexMeta("index",
                Settings.builder().put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_THRESHOLD_INDEX_INFO_SETTING.getKey(), "NOT A TIME VALUE")
                    .build()));
            fail();
        } catch (IllegalArgumentException ex) {
            assertTimeValueException(ex, "index.indexing.slowlog.threshold.index.info");
        }

        try {
            settings.updateIndexMetaData(newIndexMeta("index",
                Settings.builder().put(IndexingSlowLog.INDEX_INDEXING_SLOWLOG_THRESHOLD_INDEX_WARN_SETTING.getKey(), "NOT A TIME VALUE")
                    .build()));
            fail();
        } catch (IllegalArgumentException ex) {
            assertTimeValueException(ex, "index.indexing.slowlog.threshold.index.warn");
        }
    }

    private void assertTimeValueException(final IllegalArgumentException e, final String key) {
        final String expected = "illegal value can't update [" + key + "] from [-1] to [NOT A TIME VALUE]";
        assertThat(e, hasToString(containsString(expected)));
        assertNotNull(e.getCause());
        assertThat(e.getCause(), instanceOf(IllegalArgumentException.class));
        final IllegalArgumentException cause = (IllegalArgumentException) e.getCause();
        final String causeExpected =
            "failed to parse setting [" + key + "] with value [NOT A TIME VALUE] as a time value: unit is missing or unrecognized";
        assertThat(cause, hasToString(containsString(causeExpected)));
    }

    private IndexMetaData newIndexMeta(String name, Settings indexSettings) {
        Settings build = Settings.builder().put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 1)
            .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
            .put(indexSettings)
            .build();
        IndexMetaData metaData = IndexMetaData.builder(name).settings(build).build();
        return metaData;
    }
}
