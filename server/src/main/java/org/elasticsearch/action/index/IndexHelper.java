/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.index;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.get.GetResult;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;
import org.elasticsearch.search.lookup.SourceLookup;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

public class IndexHelper {

    /**
     * Applies {@link IndexRequest#fetchSource()} to the _source of the inserted document to be returned in a index response.
     */
    public static GetResult extractGetResult(final IndexRequest request, String concreteIndex, long seqNo, long primaryTerm, long version,
                                             final Map<String, Object> source, XContentType sourceContentType,
                                             @Nullable final BytesReference sourceAsBytes) {
        if (request.fetchSource() == null || request.fetchSource().fetchSource() == false) {
            return null;
        }

        BytesReference sourceFilteredAsBytes = sourceAsBytes;
        if (request.fetchSource().includes().length > 0 || request.fetchSource().excludes().length > 0) {
            SourceLookup sourceLookup = new SourceLookup();
            sourceLookup.setSource(source);
            Object value = sourceLookup.filter(request.fetchSource());
            try {
                final int initialCapacity = Math.min(1024, sourceAsBytes.length());
                BytesStreamOutput streamOutput = new BytesStreamOutput(initialCapacity);
                try (XContentBuilder builder = new XContentBuilder(sourceContentType.xContent(), streamOutput)) {
                    builder.value(value);
                    sourceFilteredAsBytes = BytesReference.bytes(builder);
                }
            } catch (IOException e) {
                throw new ElasticsearchException("Error filtering source", e);
            }
        }

        // TODO when using delete/none, we can still return the source as bytes by generating it (using the sourceContentType)
        return new GetResult(concreteIndex, request.type(), request.id(), seqNo, primaryTerm, version, true, sourceFilteredAsBytes,
            Collections.emptyMap(), Collections.emptyMap());
    }
    public static GetResult extractGetResult(final FetchSourceContext context, final String type, final String id, final String concreteIndex, long seqNo, long primaryTerm, long version,
                                             final Map<String, Object> source, XContentType sourceContentType,
                                             @Nullable final BytesReference sourceAsBytes) {
        if (context == null || context.fetchSource() == false) {
            return null;
        }

        BytesReference sourceFilteredAsBytes = sourceAsBytes;
        if (context.includes().length > 0 || context.excludes().length > 0) {
            SourceLookup sourceLookup = new SourceLookup();
            sourceLookup.setSource(source);
            Object value = sourceLookup.filter(context);
            try {
                final int initialCapacity = Math.min(1024, sourceAsBytes.length());
                BytesStreamOutput streamOutput = new BytesStreamOutput(initialCapacity);
                try (XContentBuilder builder = new XContentBuilder(sourceContentType.xContent(), streamOutput)) {
                    builder.value(value);
                    sourceFilteredAsBytes = BytesReference.bytes(builder);
                }
            } catch (IOException e) {
                throw new ElasticsearchException("Error filtering source", e);
            }
        }

        // TODO when using delete/none, we can still return the source as bytes by generating it (using the sourceContentType)
        return new GetResult(concreteIndex, type, id, seqNo, primaryTerm, version, true, sourceFilteredAsBytes,
            Collections.emptyMap(), Collections.emptyMap());
    }
}
