/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.search;

import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.search.aggregations.AbstractAggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.AggregatorFactories;
import org.elasticsearch.search.aggregations.AggregatorFactory;
import org.elasticsearch.search.aggregations.bucket.filter.FilterAggregationBuilder;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * An aggregation builder that blocks shard search action until the task is cancelled.
 */
public class CancellingAggregationBuilder extends AbstractAggregationBuilder<CancellingAggregationBuilder> {
    static final String NAME = "cancel";
    static final int SLEEP_TIME = 10;

    private final long randomUID;

    /**
     * Creates a {@link CancellingAggregationBuilder} with the provided <code>randomUID</code>.
     */
    public CancellingAggregationBuilder(String name, long randomUID) {
        super(name);
        this.randomUID = randomUID;
    }

    public CancellingAggregationBuilder(StreamInput in) throws IOException {
        super(in);
        this.randomUID = in.readLong();
    }

    @Override
    protected AggregationBuilder shallowCopy(AggregatorFactories.Builder factoriesBuilder, Map<String, Object> metadata) {
        return new CancellingAggregationBuilder(name, randomUID);
    }

    @Override
    public String getType() {
        return NAME;
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeLong(randomUID);
    }

    @Override
    protected XContentBuilder internalXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.endObject();
        return builder;
    }

    static final ConstructingObjectParser<CancellingAggregationBuilder, String> PARSER =
        new ConstructingObjectParser<>(NAME, false, (args, name) -> new CancellingAggregationBuilder(name, 0L));


    static CancellingAggregationBuilder fromXContent(String aggName, XContentParser parser) {
        try {
            return PARSER.apply(parser, aggName);
        } catch (IllegalArgumentException e) {
            throw new ParsingException(parser.getTokenLocation(), e.getMessage(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected AggregatorFactory doBuild(QueryShardContext queryShardContext, AggregatorFactory parent,
                                        AggregatorFactories.Builder subfactoriesBuilder) throws IOException {
        final FilterAggregationBuilder filterAgg = new FilterAggregationBuilder(name, QueryBuilders.matchAllQuery());
        filterAgg.subAggregations(subfactoriesBuilder);
        final AggregatorFactory factory = filterAgg.build(queryShardContext, parent);
        return new AggregatorFactory(name, queryShardContext, parent, subfactoriesBuilder, metaData) {
            @Override
            protected Aggregator createInternal(SearchContext searchContext,
                                                Aggregator parent,
                                                boolean collectsFromSingleBucket,
                                                List<PipelineAggregator> pipelineAggregators,
                                                Map<String, Object> metaData) throws IOException {
                while (searchContext.isCancelled() == false) {
                    try {
                        Thread.sleep(SLEEP_TIME);
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                }
                return factory.create(searchContext, parent, collectsFromSingleBucket);
            }
        };
    }
}
