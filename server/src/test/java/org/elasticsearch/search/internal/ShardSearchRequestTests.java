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

package org.elasticsearch.search.internal;

import org.elasticsearch.Version;
import org.elasticsearch.action.OriginalIndices;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.cluster.metadata.AliasMetaData;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.CheckedFunction;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.DeprecationHandler;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RandomQueryBuilder;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.InvalidAliasNameException;
import org.elasticsearch.search.AbstractSearchTestCase;
import org.elasticsearch.test.VersionUtils;
import org.elasticsearch.search.SearchSortValuesAndFormatsTests;

import java.io.IOException;

import static org.elasticsearch.index.query.AbstractQueryBuilder.parseInnerQueryBuilder;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

public class ShardSearchRequestTests extends AbstractSearchTestCase {
    private IndexMetaData baseMetaData = IndexMetaData.builder("test").settings(Settings.builder()
        .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT).build())
        .numberOfShards(1).numberOfReplicas(1).build();

    public void testSerialization() throws Exception {
        ShardSearchRequest shardSearchTransportRequest = createShardSearchRequest();
        ShardSearchRequest deserializedRequest =
            copyWriteable(shardSearchTransportRequest, namedWriteableRegistry, ShardSearchRequest::new);
        assertEquals(shardSearchTransportRequest, deserializedRequest);
    }

    public void testClone() throws Exception {
        for (int i = 0; i < 10; i++) {
            ShardSearchRequest shardSearchTransportRequest = createShardSearchRequest();
            ShardSearchRequest clone = new ShardSearchRequest(shardSearchTransportRequest);
            assertEquals(shardSearchTransportRequest, clone);
        }
    }

    public void testAllowPartialResultsSerializationPre7_0_0() throws IOException {
        Version version = VersionUtils.randomVersionBetween(random(), Version.V_6_0_0, VersionUtils.getPreviousVersion(Version.V_7_0_0));
        ShardSearchRequest shardSearchTransportRequest = createShardSearchRequest();
        ShardSearchRequest deserializedRequest =
            copyWriteable(shardSearchTransportRequest, namedWriteableRegistry, ShardSearchRequest::new, version);
        if (version.before(Version.V_6_3_0)) {
            assertFalse(deserializedRequest.allowPartialSearchResults());
        } else {
            assertEquals(shardSearchTransportRequest.allowPartialSearchResults(), deserializedRequest.allowPartialSearchResults());
        }
    }

    private ShardSearchRequest createShardSearchRequest() throws IOException {
        SearchRequest searchRequest = createSearchRequest();
        ShardId shardId = new ShardId(randomAlphaOfLengthBetween(2, 10), randomAlphaOfLengthBetween(2, 10), randomInt());
        final AliasFilter filteringAliases;
        if (randomBoolean()) {
            String[] strings = generateRandomStringArray(10, 10, false, false);
            filteringAliases = new AliasFilter(RandomQueryBuilder.createQuery(random()), strings);
        } else {
            filteringAliases = new AliasFilter(null, Strings.EMPTY_ARRAY);
        }
        final String[] routings = generateRandomStringArray(5, 10, false, true);
        ShardSearchRequest req = new ShardSearchRequest(new OriginalIndices(searchRequest), searchRequest, shardId,
            randomIntBetween(1, 100), filteringAliases, randomBoolean() ? 1.0f : randomFloat(),
            Math.abs(randomLong()), randomAlphaOfLengthBetween(3, 10), routings);
        req.canReturnNullResponseIfMatchNoDocs(randomBoolean());
        if (randomBoolean()) {
            req.setBottomSortValues(SearchSortValuesAndFormatsTests.randomInstance());
        }
        return req;
    }

    public void testFilteringAliases() throws Exception {
        IndexMetaData indexMetaData = baseMetaData;
        indexMetaData = add(indexMetaData, "cats", filter(termQuery("animal", "cat")));
        indexMetaData = add(indexMetaData, "dogs", filter(termQuery("animal", "dog")));
        indexMetaData = add(indexMetaData, "all", null);

        assertThat(indexMetaData.getAliases().containsKey("cats"), equalTo(true));
        assertThat(indexMetaData.getAliases().containsKey("dogs"), equalTo(true));
        assertThat(indexMetaData.getAliases().containsKey("turtles"), equalTo(false));

        assertEquals(aliasFilter(indexMetaData, "cats"), QueryBuilders.termQuery("animal", "cat"));
        assertEquals(aliasFilter(indexMetaData, "cats", "dogs"), QueryBuilders.boolQuery().should(QueryBuilders.termQuery("animal", "cat"))
            .should(QueryBuilders.termQuery("animal", "dog")));

        // Non-filtering alias should turn off all filters because filters are ORed
        assertThat(aliasFilter(indexMetaData,"all"), nullValue());
        assertThat(aliasFilter(indexMetaData, "cats", "all"), nullValue());
        assertThat(aliasFilter(indexMetaData, "all", "cats"), nullValue());

        indexMetaData = add(indexMetaData, "cats", filter(termQuery("animal", "feline")));
        indexMetaData = add(indexMetaData, "dogs", filter(termQuery("animal", "canine")));
        assertEquals(aliasFilter(indexMetaData, "dogs", "cats"),QueryBuilders.boolQuery()
            .should(QueryBuilders.termQuery("animal", "canine"))
            .should(QueryBuilders.termQuery("animal", "feline")));
    }

    public void testRemovedAliasFilter() throws Exception {
        IndexMetaData indexMetaData = baseMetaData;
        indexMetaData = add(indexMetaData, "cats", filter(termQuery("animal", "cat")));
        indexMetaData = remove(indexMetaData, "cats");
        try {
            aliasFilter(indexMetaData, "cats");
            fail("Expected InvalidAliasNameException");
        } catch (InvalidAliasNameException e) {
            assertThat(e.getMessage(), containsString("Invalid alias name [cats]"));
        }
    }

    public void testUnknownAliasFilter() throws Exception {
        IndexMetaData indexMetaData = baseMetaData;
        indexMetaData = add(indexMetaData, "cats", filter(termQuery("animal", "cat")));
        indexMetaData = add(indexMetaData, "dogs", filter(termQuery("animal", "dog")));
        IndexMetaData finalIndexMetadata = indexMetaData;
        expectThrows(InvalidAliasNameException.class, () -> aliasFilter(finalIndexMetadata, "unknown"));
    }

    private static void assertEquals(ShardSearchRequest orig, ShardSearchRequest copy) throws IOException {
        assertEquals(orig.scroll(), copy.scroll());
        assertEquals(orig.getAliasFilter(), copy.getAliasFilter());
        assertArrayEquals(orig.indices(), copy.indices());
        assertEquals(orig.indicesOptions(), copy.indicesOptions());
        assertEquals(orig.nowInMillis(), copy.nowInMillis());
        assertEquals(orig.source(), copy.source());
        assertEquals(orig.searchType(), copy.searchType());
        assertEquals(orig.shardId(), copy.shardId());
        assertEquals(orig.numberOfShards(), copy.numberOfShards());
        assertArrayEquals(orig.indexRoutings(), copy.indexRoutings());
        assertEquals(orig.preference(), copy.preference());
        assertEquals(orig.cacheKey(), copy.cacheKey());
        assertNotSame(orig, copy);
        assertEquals(orig.getAliasFilter(), copy.getAliasFilter());
        assertEquals(orig.indexBoost(), copy.indexBoost(), 0.0f);
        assertEquals(orig.getClusterAlias(), copy.getClusterAlias());
        assertEquals(orig.allowPartialSearchResults(), copy.allowPartialSearchResults());
        assertEquals(orig.canReturnNullResponseIfMatchNoDocs(),
            orig.canReturnNullResponseIfMatchNoDocs());
    }

    public static CompressedXContent filter(QueryBuilder filterBuilder) throws IOException {
        XContentBuilder builder = XContentFactory.jsonBuilder();
        filterBuilder.toXContent(builder, ToXContent.EMPTY_PARAMS);
        builder.close();
        return new CompressedXContent(Strings.toString(builder));
    }

    private IndexMetaData remove(IndexMetaData indexMetaData, String alias) {
        return IndexMetaData.builder(indexMetaData).removeAlias(alias).build();
    }

    private IndexMetaData add(IndexMetaData indexMetaData, String alias, @Nullable CompressedXContent filter) {
        return IndexMetaData.builder(indexMetaData).putAlias(AliasMetaData.builder(alias).filter(filter).build()).build();
    }

    public QueryBuilder aliasFilter(IndexMetaData indexMetaData, String... aliasNames) {
        CheckedFunction<byte[], QueryBuilder, IOException> filterParser = bytes -> {
            try (XContentParser parser = XContentFactory.xContent(bytes)
                    .createParser(xContentRegistry(), DeprecationHandler.THROW_UNSUPPORTED_OPERATION, bytes)) {
                return parseInnerQueryBuilder(parser);
            }
        };
        return ShardSearchRequest.parseAliasFilter(filterParser, indexMetaData, aliasNames);
    }
}
