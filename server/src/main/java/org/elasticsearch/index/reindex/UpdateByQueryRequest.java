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

package org.elasticsearch.index.reindex;

import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;
import org.elasticsearch.tasks.TaskId;

import java.io.IOException;

/**
 * Request to update some documents. That means you can't change their type, id, index, or anything like that. This implements
 * CompositeIndicesRequest but in a misleading way. Rather than returning all the subrequests that it will make it tries to return a
 * representative set of subrequests. This is best-effort but better than {@linkplain ReindexRequest} because scripts can't change the
 * destination index and things.
 */
public class UpdateByQueryRequest extends AbstractBulkIndexByScrollRequest<UpdateByQueryRequest>
    implements IndicesRequest.Replaceable, ToXContentObject {
    /**
     * Ingest pipeline to set on index requests made by this action.
     */
    private String pipeline;
    private FetchSourceContext fetchSourceContextNew;
    private FetchSourceContext fetchSourceContextOld;

    public UpdateByQueryRequest() {
        this(new SearchRequest());
    }

    public UpdateByQueryRequest(String... indices) {
        this(new SearchRequest(indices));
    }

    UpdateByQueryRequest(SearchRequest search) {
        this(search, true);
    }

    public UpdateByQueryRequest(StreamInput in) throws IOException {
        super(in);
        pipeline = in.readOptionalString();
    }

    private UpdateByQueryRequest(SearchRequest search, boolean setDefaults) {
        super(search, setDefaults);
    }

    /**
     * Set the ingest pipeline to set on index requests made by this action.
     */
    public UpdateByQueryRequest setPipeline(String pipeline) {
        this.pipeline = pipeline;
        return this;
    }

    /**
     * Set the query for selective update
     */
    public UpdateByQueryRequest setQuery(QueryBuilder query) {
        if (query != null) {
            getSearchRequest().source().query(query);
        }
        return this;
    }

    /**
     * Set the document types for the update
     * @deprecated Types are in the process of being removed. Instead of
     * using a type, prefer to filter on a field of the document.
     */
    @Deprecated
    public UpdateByQueryRequest setDocTypes(String... types) {
        if (types != null) {
            getSearchRequest().types(types);
        }
        return this;
    }

    /**
     * Set routing limiting the process to the shards that match that routing value
     */
    public UpdateByQueryRequest setRouting(String routing) {
        if (routing != null) {
            getSearchRequest().routing(routing);
        }
        return this;
    }

    /**
     * The scroll size to control number of documents processed per batch
     */
    public UpdateByQueryRequest setBatchSize(int size) {
        getSearchRequest().source().size(size);
        return this;
    }

    /**
     * Set the IndicesOptions for controlling unavailable indices
     */
    public UpdateByQueryRequest setIndicesOptions(IndicesOptions indicesOptions) {
        getSearchRequest().indicesOptions(indicesOptions);
        return this;
    }

    /**
     * Gets the batch size for this request
     */
    public int getBatchSize() {
        return getSearchRequest().source().size();
    }

    /**
     * Gets the routing value used for this request
     */
    public String getRouting() {
        return getSearchRequest().routing();
    }

    /**
     * Gets the document types on which this request would be executed. Returns an empty array if all
     * types are to be processed.
     * @deprecated Types are in the process of being removed. Instead of
     * using a type, prefer to filter on a field of the document.
     */
    @Deprecated
    public String[] getDocTypes() {
        if (getSearchRequest().types() != null) {
            return getSearchRequest().types();
        } else {
            return new String[0];
        }
    }

    /**
     * Ingest pipeline to set on index requests made by this action.
     */
    public String getPipeline() {
        return pipeline;
    }

    @Override
    protected UpdateByQueryRequest self() {
        return this;
    }

    @Override
    public UpdateByQueryRequest forSlice(TaskId slicingTask, SearchRequest slice, int totalSlices) {
        UpdateByQueryRequest request = doForSlice(new UpdateByQueryRequest(slice, false), slicingTask, totalSlices);
        request.setPipeline(pipeline);
        return request;
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        b.append("update-by-query ");
        searchToString(b);
        return b.toString();
    }

    //update by query updates all documents that match a query. The indices and indices options that affect how
    //indices are resolved depend entirely on the inner search request. That's why the following methods delegate to it.
    @Override
    public IndicesRequest indices(String... indices) {
        assert getSearchRequest() != null;
        getSearchRequest().indices(indices);
        return this;
    }

    @Override
    public String[] indices() {
        assert getSearchRequest() != null;
        return getSearchRequest().indices();
    }

    @Override
    public IndicesOptions indicesOptions() {
        assert getSearchRequest() != null;
        return getSearchRequest().indicesOptions();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeOptionalString(pipeline);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        if (getScript() != null) {
            builder.field("script");
            getScript().toXContent(builder, params);
        }
        getSearchRequest().source().innerToXContent(builder, params);
        builder.endObject();
        return builder;
    }






    /**
     * Indicate that _source should be returned with every hit, with an
     * "include" and/or "exclude" set which can include simple wildcard
     * elements.
     *
     * @param include
     *            An optional include (optionally wildcarded) pattern to filter
     *            the returned _source
     * @param exclude
     *            An optional exclude (optionally wildcarded) pattern to filter
     *            the returned _source
     */
    public UpdateByQueryRequest fetchSourceNew(@Nullable String include, @Nullable String exclude) {
        FetchSourceContext context = this.fetchSourceContextNew == null ? FetchSourceContext.FETCH_SOURCE : this.fetchSourceContextNew;
        String[] includes = include == null ? Strings.EMPTY_ARRAY : new String[]{include};
        String[] excludes = exclude == null ? Strings.EMPTY_ARRAY : new String[]{exclude};
        this.fetchSourceContextNew = new FetchSourceContext(context.fetchSource(), includes, excludes);
        return this;
    }

    /**
     * Indicate that _source should be returned, with an
     * "include" and/or "exclude" set which can include simple wildcard
     * elements.
     *
     * @param includes
     *            An optional list of include (optionally wildcarded) pattern to
     *            filter the returned _source
     * @param excludes
     *            An optional list of exclude (optionally wildcarded) pattern to
     *            filter the returned _source
     */
    public UpdateByQueryRequest fetchSourceNew(@Nullable String[] includes, @Nullable String[] excludes) {
        FetchSourceContext context = this.fetchSourceContextNew == null ? FetchSourceContext.FETCH_SOURCE : this.fetchSourceContextNew;
        this.fetchSourceContextNew = new FetchSourceContext(context.fetchSource(), includes, excludes);
        return this;
    }

    /**
     * Indicates whether the response should contain the updated _source.
     */
    public UpdateByQueryRequest fetchSourceNew(boolean fetchSource) {
        FetchSourceContext context = this.fetchSourceContextNew == null ? FetchSourceContext.FETCH_SOURCE : this.fetchSourceContextNew;
        this.fetchSourceContextNew = new FetchSourceContext(fetchSource, context.includes(), context.excludes());
        return this;
    }

    /**
     * Explicitly set the fetch source context for this request
     */
    public UpdateByQueryRequest fetchSourceNew(FetchSourceContext context) {
        this.fetchSourceContextNew = context;
        return this;
    }

    /**
     * Gets the {@link FetchSourceContext} which defines how the _source should
     * be fetched.
     */
    public FetchSourceContext fetchSourceNew() {
        return fetchSourceContextNew;
    }





    /**
     * Indicate that _source should be returned with every hit before applying script (i.e. before update document), with an
     * "include" and/or "exclude" set which can include simple wildcard
     * elements.
     *
     * @param include
     *            An optional include (optionally wildcarded) pattern to filter
     *            the returned _source
     * @param exclude
     *            An optional exclude (optionally wildcarded) pattern to filter
     *            the returned _source
     */
    public UpdateByQueryRequest fetchSourceOld(@Nullable String include, @Nullable String exclude) {
        FetchSourceContext context = this.fetchSourceContextOld == null ? FetchSourceContext.FETCH_SOURCE : this.fetchSourceContextOld;
        String[] includes = include == null ? Strings.EMPTY_ARRAY : new String[]{include};
        String[] excludes = exclude == null ? Strings.EMPTY_ARRAY : new String[]{exclude};
        this.fetchSourceContextOld = new FetchSourceContext(context.fetchSource(), includes, excludes);
        return this;
    }

    /**
     * Indicate that _source should be returned, with an
     * "include" and/or "exclude" set which can include simple wildcard
     * elements.
     *
     * @param includes
     *            An optional list of include (optionally wildcarded) pattern to
     *            filter the returned _source
     * @param excludes
     *            An optional list of exclude (optionally wildcarded) pattern to
     *            filter the returned _source
     */
    public UpdateByQueryRequest fetchSourcOld(@Nullable String[] includes, @Nullable String[] excludes) {
        FetchSourceContext context = this.fetchSourceContextOld == null ? FetchSourceContext.FETCH_SOURCE : this.fetchSourceContextOld;
        this.fetchSourceContextOld = new FetchSourceContext(context.fetchSource(), includes, excludes);
        return this;
    }

    /**
     * Indicates whether the response should contain the Old _source.
     */
    public UpdateByQueryRequest fetchSourceOld(boolean fetchSource) {
        FetchSourceContext context = this.fetchSourceContextOld == null ? FetchSourceContext.FETCH_SOURCE : this.fetchSourceContextOld;
        this.fetchSourceContextOld = new FetchSourceContext(fetchSource, context.includes(), context.excludes());
        return this;
    }

    /**
     * Explicitly set the fetch source context for this request
     */
    public UpdateByQueryRequest fetchSourceOld(FetchSourceContext context) {
        this.fetchSourceContextOld = context;
        return this;
    }

    /**
     * Gets the {@link FetchSourceContext} which defines how the _source should
     * be fetched.
     */
    public FetchSourceContext fetchSourceOld() {
        return fetchSourceContextOld;
    }

}
