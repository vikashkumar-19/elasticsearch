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

package org.elasticsearch.search;

import org.apache.lucene.search.BooleanQuery;
import org.elasticsearch.common.NamedRegistry;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.geo.GeoShapeType;
import org.elasticsearch.common.geo.ShapesAvailability;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry.Entry;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.ParseFieldRegistry;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.BoostingQueryBuilder;
import org.elasticsearch.index.query.CommonTermsQueryBuilder;
import org.elasticsearch.index.query.ConstantScoreQueryBuilder;
import org.elasticsearch.index.query.DisMaxQueryBuilder;
import org.elasticsearch.index.query.DistanceFeatureQueryBuilder;
import org.elasticsearch.index.query.ExistsQueryBuilder;
import org.elasticsearch.index.query.FieldMaskingSpanQueryBuilder;
import org.elasticsearch.index.query.FuzzyQueryBuilder;
import org.elasticsearch.index.query.GeoBoundingBoxQueryBuilder;
import org.elasticsearch.index.query.GeoDistanceQueryBuilder;
import org.elasticsearch.index.query.GeoPolygonQueryBuilder;
import org.elasticsearch.index.query.GeoShapeQueryBuilder;
import org.elasticsearch.index.query.IdsQueryBuilder;
import org.elasticsearch.index.query.IntervalQueryBuilder;
import org.elasticsearch.index.query.IntervalsSourceProvider;
import org.elasticsearch.index.query.MatchAllQueryBuilder;
import org.elasticsearch.index.query.MatchBoolPrefixQueryBuilder;
import org.elasticsearch.index.query.MatchNoneQueryBuilder;
import org.elasticsearch.index.query.MatchPhrasePrefixQueryBuilder;
import org.elasticsearch.index.query.MatchPhraseQueryBuilder;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.MoreLikeThisQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.index.query.NestedQueryBuilder;
import org.elasticsearch.index.query.PrefixQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryStringQueryBuilder;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.index.query.RegexpQueryBuilder;
import org.elasticsearch.index.query.ScriptQueryBuilder;
import org.elasticsearch.index.query.SimpleQueryStringBuilder;
import org.elasticsearch.index.query.SpanContainingQueryBuilder;
import org.elasticsearch.index.query.SpanFirstQueryBuilder;
import org.elasticsearch.index.query.SpanMultiTermQueryBuilder;
import org.elasticsearch.index.query.SpanNearQueryBuilder;
import org.elasticsearch.index.query.SpanNearQueryBuilder.SpanGapQueryBuilder;
import org.elasticsearch.index.query.SpanNotQueryBuilder;
import org.elasticsearch.index.query.SpanOrQueryBuilder;
import org.elasticsearch.index.query.SpanTermQueryBuilder;
import org.elasticsearch.index.query.SpanWithinQueryBuilder;
import org.elasticsearch.index.query.TermQueryBuilder;
import org.elasticsearch.index.query.TermsQueryBuilder;
import org.elasticsearch.index.query.TermsSetQueryBuilder;
import org.elasticsearch.index.query.TypeQueryBuilder;
import org.elasticsearch.index.query.WildcardQueryBuilder;
import org.elasticsearch.index.query.WrapperQueryBuilder;
import org.elasticsearch.index.query.functionscore.ExponentialDecayFunctionBuilder;
import org.elasticsearch.index.query.functionscore.FieldValueFactorFunctionBuilder;
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder;
import org.elasticsearch.index.query.functionscore.GaussDecayFunctionBuilder;
import org.elasticsearch.index.query.functionscore.LinearDecayFunctionBuilder;
import org.elasticsearch.index.query.functionscore.RandomScoreFunctionBuilder;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilder;
import org.elasticsearch.index.query.functionscore.ScriptScoreFunctionBuilder;
import org.elasticsearch.index.query.functionscore.ScriptScoreQueryBuilder;
import org.elasticsearch.index.query.functionscore.WeightBuilder;
import org.elasticsearch.plugins.SearchPlugin;
import org.elasticsearch.plugins.SearchPlugin.AggregationSpec;
import org.elasticsearch.plugins.SearchPlugin.FetchPhaseConstructionContext;
import org.elasticsearch.plugins.SearchPlugin.PipelineAggregationSpec;
import org.elasticsearch.plugins.SearchPlugin.QuerySpec;
import org.elasticsearch.plugins.SearchPlugin.RescorerSpec;
import org.elasticsearch.plugins.SearchPlugin.ScoreFunctionSpec;
import org.elasticsearch.plugins.SearchPlugin.SearchExtSpec;
import org.elasticsearch.plugins.SearchPlugin.SearchExtensionSpec;
import org.elasticsearch.plugins.SearchPlugin.SignificanceHeuristicSpec;
import org.elasticsearch.plugins.SearchPlugin.SuggesterSpec;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.BaseAggregationBuilder;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.PipelineAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.adjacency.AdjacencyMatrixAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.adjacency.InternalAdjacencyMatrix;
import org.elasticsearch.search.aggregations.bucket.composite.CompositeAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.composite.InternalComposite;
import org.elasticsearch.search.aggregations.bucket.filter.FilterAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.filter.FiltersAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.filter.InternalFilter;
import org.elasticsearch.search.aggregations.bucket.filter.InternalFilters;
import org.elasticsearch.search.aggregations.bucket.geogrid.GeoHashGridAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.geogrid.GeoTileGridAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.geogrid.InternalGeoHashGrid;
import org.elasticsearch.search.aggregations.bucket.geogrid.InternalGeoTileGrid;
import org.elasticsearch.search.aggregations.bucket.global.GlobalAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.global.InternalGlobal;
import org.elasticsearch.search.aggregations.bucket.histogram.AutoDateHistogramAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.histogram.HistogramAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.histogram.InternalAutoDateHistogram;
import org.elasticsearch.search.aggregations.bucket.histogram.InternalDateHistogram;
import org.elasticsearch.search.aggregations.bucket.histogram.InternalHistogram;
import org.elasticsearch.search.aggregations.bucket.missing.InternalMissing;
import org.elasticsearch.search.aggregations.bucket.missing.MissingAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.nested.InternalNested;
import org.elasticsearch.search.aggregations.bucket.nested.InternalReverseNested;
import org.elasticsearch.search.aggregations.bucket.nested.NestedAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.nested.ReverseNestedAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.range.DateRangeAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.range.GeoDistanceAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.range.InternalBinaryRange;
import org.elasticsearch.search.aggregations.bucket.range.InternalDateRange;
import org.elasticsearch.search.aggregations.bucket.range.InternalGeoDistance;
import org.elasticsearch.search.aggregations.bucket.range.InternalRange;
import org.elasticsearch.search.aggregations.bucket.range.IpRangeAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.range.RangeAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.sampler.DiversifiedAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.sampler.InternalSampler;
import org.elasticsearch.search.aggregations.bucket.sampler.SamplerAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.sampler.UnmappedSampler;
import org.elasticsearch.search.aggregations.bucket.significant.SignificantLongTerms;
import org.elasticsearch.search.aggregations.bucket.significant.SignificantStringTerms;
import org.elasticsearch.search.aggregations.bucket.significant.SignificantTermsAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.significant.SignificantTextAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.significant.UnmappedSignificantTerms;
import org.elasticsearch.search.aggregations.bucket.significant.heuristics.ChiSquare;
import org.elasticsearch.search.aggregations.bucket.significant.heuristics.GND;
import org.elasticsearch.search.aggregations.bucket.significant.heuristics.JLHScore;
import org.elasticsearch.search.aggregations.bucket.significant.heuristics.MutualInformation;
import org.elasticsearch.search.aggregations.bucket.significant.heuristics.PercentageScore;
import org.elasticsearch.search.aggregations.bucket.significant.heuristics.ScriptHeuristic;
import org.elasticsearch.search.aggregations.bucket.significant.heuristics.SignificanceHeuristic;
import org.elasticsearch.search.aggregations.bucket.terms.DoubleTerms;
import org.elasticsearch.search.aggregations.bucket.terms.LongRareTerms;
import org.elasticsearch.search.aggregations.bucket.terms.LongTerms;
import org.elasticsearch.search.aggregations.bucket.terms.RareTermsAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.terms.StringRareTerms;
import org.elasticsearch.search.aggregations.bucket.terms.StringTerms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.terms.UnmappedRareTerms;
import org.elasticsearch.search.aggregations.bucket.terms.UnmappedTerms;
import org.elasticsearch.search.aggregations.metrics.AvgAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.CardinalityAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.ExtendedStatsAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.GeoBoundsAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.GeoCentroidAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.InternalAvg;
import org.elasticsearch.search.aggregations.metrics.InternalCardinality;
import org.elasticsearch.search.aggregations.metrics.InternalExtendedStats;
import org.elasticsearch.search.aggregations.metrics.InternalGeoBounds;
import org.elasticsearch.search.aggregations.metrics.InternalGeoCentroid;
import org.elasticsearch.search.aggregations.metrics.InternalHDRPercentileRanks;
import org.elasticsearch.search.aggregations.metrics.InternalHDRPercentiles;
import org.elasticsearch.search.aggregations.metrics.InternalMax;
import org.elasticsearch.search.aggregations.metrics.InternalMedianAbsoluteDeviation;
import org.elasticsearch.search.aggregations.metrics.InternalMin;
import org.elasticsearch.search.aggregations.metrics.InternalScriptedMetric;
import org.elasticsearch.search.aggregations.metrics.InternalStats;
import org.elasticsearch.search.aggregations.metrics.InternalSum;
import org.elasticsearch.search.aggregations.metrics.InternalTDigestPercentileRanks;
import org.elasticsearch.search.aggregations.metrics.InternalTDigestPercentiles;
import org.elasticsearch.search.aggregations.metrics.InternalTopHits;
import org.elasticsearch.search.aggregations.metrics.InternalValueCount;
import org.elasticsearch.search.aggregations.metrics.InternalWeightedAvg;
import org.elasticsearch.search.aggregations.metrics.MaxAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.MedianAbsoluteDeviationAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.MinAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.PercentileRanksAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.PercentilesAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.ScriptedMetricAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.StatsAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.SumAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.TopHitsAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.ValueCountAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.WeightedAvgAggregationBuilder;
import org.elasticsearch.search.aggregations.pipeline.AvgBucketPipelineAggregationBuilder;
import org.elasticsearch.search.aggregations.pipeline.AvgBucketPipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.BucketScriptPipelineAggregationBuilder;
import org.elasticsearch.search.aggregations.pipeline.BucketScriptPipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.BucketSelectorPipelineAggregationBuilder;
import org.elasticsearch.search.aggregations.pipeline.BucketSelectorPipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.BucketSortPipelineAggregationBuilder;
import org.elasticsearch.search.aggregations.pipeline.BucketSortPipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.CumulativeSumPipelineAggregationBuilder;
import org.elasticsearch.search.aggregations.pipeline.CumulativeSumPipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.DerivativePipelineAggregationBuilder;
import org.elasticsearch.search.aggregations.pipeline.DerivativePipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.EwmaModel;
import org.elasticsearch.search.aggregations.pipeline.ExtendedStatsBucketParser;
import org.elasticsearch.search.aggregations.pipeline.ExtendedStatsBucketPipelineAggregationBuilder;
import org.elasticsearch.search.aggregations.pipeline.ExtendedStatsBucketPipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.HoltLinearModel;
import org.elasticsearch.search.aggregations.pipeline.HoltWintersModel;
import org.elasticsearch.search.aggregations.pipeline.InternalBucketMetricValue;
import org.elasticsearch.search.aggregations.pipeline.InternalDerivative;
import org.elasticsearch.search.aggregations.pipeline.InternalExtendedStatsBucket;
import org.elasticsearch.search.aggregations.pipeline.InternalPercentilesBucket;
import org.elasticsearch.search.aggregations.pipeline.InternalSimpleValue;
import org.elasticsearch.search.aggregations.pipeline.InternalStatsBucket;
import org.elasticsearch.search.aggregations.pipeline.LinearModel;
import org.elasticsearch.search.aggregations.pipeline.MaxBucketPipelineAggregationBuilder;
import org.elasticsearch.search.aggregations.pipeline.MaxBucketPipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.MinBucketPipelineAggregationBuilder;
import org.elasticsearch.search.aggregations.pipeline.MinBucketPipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.MovAvgModel;
import org.elasticsearch.search.aggregations.pipeline.MovAvgPipelineAggregationBuilder;
import org.elasticsearch.search.aggregations.pipeline.MovAvgPipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.MovFnPipelineAggregationBuilder;
import org.elasticsearch.search.aggregations.pipeline.MovFnPipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.PercentilesBucketPipelineAggregationBuilder;
import org.elasticsearch.search.aggregations.pipeline.PercentilesBucketPipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.SerialDiffPipelineAggregationBuilder;
import org.elasticsearch.search.aggregations.pipeline.SerialDiffPipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.SimpleModel;
import org.elasticsearch.search.aggregations.pipeline.StatsBucketPipelineAggregationBuilder;
import org.elasticsearch.search.aggregations.pipeline.StatsBucketPipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.SumBucketPipelineAggregationBuilder;
import org.elasticsearch.search.aggregations.pipeline.SumBucketPipelineAggregator;
import org.elasticsearch.search.fetch.FetchPhase;
import org.elasticsearch.search.fetch.FetchSubPhase;
import org.elasticsearch.search.fetch.subphase.FetchDocValuesPhase;
import org.elasticsearch.search.fetch.subphase.ExplainPhase;
import org.elasticsearch.search.fetch.subphase.FetchSourcePhase;
import org.elasticsearch.search.fetch.subphase.MatchedQueriesPhase;
import org.elasticsearch.search.fetch.subphase.FetchScorePhase;
import org.elasticsearch.search.fetch.subphase.ScriptFieldsPhase;
import org.elasticsearch.search.fetch.subphase.SeqNoPrimaryTermPhase;
import org.elasticsearch.search.fetch.subphase.FetchVersionPhase;
import org.elasticsearch.search.fetch.subphase.highlight.FastVectorHighlighter;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightPhase;
import org.elasticsearch.search.fetch.subphase.highlight.Highlighter;
import org.elasticsearch.search.fetch.subphase.highlight.PlainHighlighter;
import org.elasticsearch.search.fetch.subphase.highlight.UnifiedHighlighter;
import org.elasticsearch.search.rescore.QueryRescorerBuilder;
import org.elasticsearch.search.rescore.RescorerBuilder;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.GeoDistanceSortBuilder;
import org.elasticsearch.search.sort.ScoreSortBuilder;
import org.elasticsearch.search.sort.ScriptSortBuilder;
import org.elasticsearch.search.sort.SortBuilder;
import org.elasticsearch.search.sort.SortValue;
import org.elasticsearch.search.suggest.Suggest;
import org.elasticsearch.search.suggest.SuggestionBuilder;
import org.elasticsearch.search.suggest.completion.CompletionSuggestion;
import org.elasticsearch.search.suggest.completion.CompletionSuggestionBuilder;
import org.elasticsearch.search.suggest.phrase.Laplace;
import org.elasticsearch.search.suggest.phrase.LinearInterpolation;
import org.elasticsearch.search.suggest.phrase.PhraseSuggestion;
import org.elasticsearch.search.suggest.phrase.PhraseSuggestionBuilder;
import org.elasticsearch.search.suggest.phrase.SmoothingModel;
import org.elasticsearch.search.suggest.phrase.StupidBackoff;
import org.elasticsearch.search.suggest.term.TermSuggestion;
import org.elasticsearch.search.suggest.term.TermSuggestionBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;
import static org.elasticsearch.index.query.CommonTermsQueryBuilder.COMMON_TERMS_QUERY_DEPRECATION_MSG;

/**
 * Sets up things that can be done at search time like queries, aggregations, and suggesters.
 */
public class SearchModule {
    public static final Setting<Integer> INDICES_MAX_CLAUSE_COUNT_SETTING = Setting.intSetting("indices.query.bool.max_clause_count",
            1024, 1, Integer.MAX_VALUE, Setting.Property.NodeScope);

    private final boolean transportClient;
    private final Map<String, Highlighter> highlighters;
    private final ParseFieldRegistry<MovAvgModel.AbstractModelParser> movingAverageModelParserRegistry = new ParseFieldRegistry<>(
            "moving_avg_model");

    private final List<FetchSubPhase> fetchSubPhases = new ArrayList<>();

    private final Settings settings;
    private final List<NamedWriteableRegistry.Entry> namedWriteables = new ArrayList<>();
    private final List<NamedXContentRegistry.Entry> namedXContents = new ArrayList<>();

    /**
     * Constructs a new SearchModule object
     *
     * NOTE: This constructor should not be called in production unless an accurate {@link Settings} object is provided.
     *       When constructed, a static flag is set in Lucene {@link BooleanQuery#setMaxClauseCount} according to the settings.
     *
     * @param settings Current settings
     * @param transportClient Is this being constructed in the TransportClient or not
     * @param plugins List of included {@link SearchPlugin} objects.
     */
    public SearchModule(Settings settings, boolean transportClient, List<SearchPlugin> plugins) {
        this.settings = settings;
        this.transportClient = transportClient;
        registerSuggesters(plugins);
        highlighters = setupHighlighters(settings, plugins);
        registerScoreFunctions(plugins);
        registerQueryParsers(plugins);
        registerRescorers(plugins);
        registerSorts();
        registerValueFormats();
        registerSignificanceHeuristics(plugins);
        registerMovingAverageModels(plugins);
        registerAggregations(plugins);
        registerPipelineAggregations(plugins);
        registerFetchSubPhases(plugins);
        registerSearchExts(plugins);
        registerShapes();
        registerIntervalsSourceProviders();
        namedWriteables.addAll(SortValue.namedWriteables());
    }

    public List<NamedWriteableRegistry.Entry> getNamedWriteables() {
        return namedWriteables;
    }

    public List<NamedXContentRegistry.Entry> getNamedXContents() {
        return namedXContents;
    }

    /**
     * Returns the {@link Highlighter} registry
     */
    public Map<String, Highlighter> getHighlighters() {
        return highlighters;
    }

    /**
     * The registry of {@link MovAvgModel}s.
     */
    public ParseFieldRegistry<MovAvgModel.AbstractModelParser> getMovingAverageModelParserRegistry() {
        return movingAverageModelParserRegistry;
    }

    private void registerAggregations(List<SearchPlugin> plugins) {
        registerAggregation(new AggregationSpec(AvgAggregationBuilder.NAME, AvgAggregationBuilder::new, AvgAggregationBuilder.PARSER)
                .addResultReader(InternalAvg::new));
        registerAggregation(new AggregationSpec(WeightedAvgAggregationBuilder.NAME, WeightedAvgAggregationBuilder::new,
            WeightedAvgAggregationBuilder.PARSER).addResultReader(InternalWeightedAvg::new));
        registerAggregation(new AggregationSpec(SumAggregationBuilder.NAME, SumAggregationBuilder::new, SumAggregationBuilder.PARSER)
                .addResultReader(InternalSum::new));
        registerAggregation(new AggregationSpec(MinAggregationBuilder.NAME, MinAggregationBuilder::new, MinAggregationBuilder.PARSER)
                .addResultReader(InternalMin::new));
        registerAggregation(new AggregationSpec(MaxAggregationBuilder.NAME, MaxAggregationBuilder::new, MaxAggregationBuilder.PARSER)
                .addResultReader(InternalMax::new));
        registerAggregation(new AggregationSpec(StatsAggregationBuilder.NAME, StatsAggregationBuilder::new, StatsAggregationBuilder.PARSER)
                .addResultReader(InternalStats::new));
        registerAggregation(new AggregationSpec(ExtendedStatsAggregationBuilder.NAME, ExtendedStatsAggregationBuilder::new,
                ExtendedStatsAggregationBuilder.PARSER).addResultReader(InternalExtendedStats::new));
        registerAggregation(new AggregationSpec(ValueCountAggregationBuilder.NAME, ValueCountAggregationBuilder::new,
                ValueCountAggregationBuilder.PARSER).addResultReader(InternalValueCount::new));
        registerAggregation(new AggregationSpec(PercentilesAggregationBuilder.NAME, PercentilesAggregationBuilder::new,
                PercentilesAggregationBuilder::parse)
                    .addResultReader(InternalTDigestPercentiles.NAME, InternalTDigestPercentiles::new)
                    .addResultReader(InternalHDRPercentiles.NAME, InternalHDRPercentiles::new));
        registerAggregation(new AggregationSpec(PercentileRanksAggregationBuilder.NAME, PercentileRanksAggregationBuilder::new,
                PercentileRanksAggregationBuilder::parse)
                        .addResultReader(InternalTDigestPercentileRanks.NAME, InternalTDigestPercentileRanks::new)
                        .addResultReader(InternalHDRPercentileRanks.NAME, InternalHDRPercentileRanks::new));
        registerAggregation(new AggregationSpec(MedianAbsoluteDeviationAggregationBuilder.NAME,
                MedianAbsoluteDeviationAggregationBuilder::new, MedianAbsoluteDeviationAggregationBuilder.PARSER)
                        .addResultReader(InternalMedianAbsoluteDeviation::new));
        registerAggregation(new AggregationSpec(CardinalityAggregationBuilder.NAME, CardinalityAggregationBuilder::new,
                CardinalityAggregationBuilder.PARSER).addResultReader(InternalCardinality::new));
        registerAggregation(new AggregationSpec(GlobalAggregationBuilder.NAME, GlobalAggregationBuilder::new,
                GlobalAggregationBuilder::parse).addResultReader(InternalGlobal::new));
        registerAggregation(new AggregationSpec(MissingAggregationBuilder.NAME, MissingAggregationBuilder::new,
                MissingAggregationBuilder.PARSER).addResultReader(InternalMissing::new));
        registerAggregation(new AggregationSpec(FilterAggregationBuilder.NAME, FilterAggregationBuilder::new,
                FilterAggregationBuilder::parse).addResultReader(InternalFilter::new));
        registerAggregation(new AggregationSpec(FiltersAggregationBuilder.NAME, FiltersAggregationBuilder::new,
                FiltersAggregationBuilder::parse).addResultReader(InternalFilters::new));
        registerAggregation(new AggregationSpec(AdjacencyMatrixAggregationBuilder.NAME, AdjacencyMatrixAggregationBuilder::new,
                AdjacencyMatrixAggregationBuilder::parse).addResultReader(InternalAdjacencyMatrix::new));
        registerAggregation(new AggregationSpec(SamplerAggregationBuilder.NAME, SamplerAggregationBuilder::new,
                SamplerAggregationBuilder::parse)
                    .addResultReader(InternalSampler.NAME, InternalSampler::new)
                    .addResultReader(UnmappedSampler.NAME, UnmappedSampler::new));
        registerAggregation(new AggregationSpec(DiversifiedAggregationBuilder.NAME, DiversifiedAggregationBuilder::new,
                DiversifiedAggregationBuilder.PARSER)
                    /* Reuses result readers from SamplerAggregator*/);
        registerAggregation(new AggregationSpec(TermsAggregationBuilder.NAME, TermsAggregationBuilder::new,
                TermsAggregationBuilder.PARSER)
                    .addResultReader(StringTerms.NAME, StringTerms::new)
                    .addResultReader(UnmappedTerms.NAME, UnmappedTerms::new)
                    .addResultReader(LongTerms.NAME, LongTerms::new)
                    .addResultReader(DoubleTerms.NAME, DoubleTerms::new));
        registerAggregation(new AggregationSpec(RareTermsAggregationBuilder.NAME, RareTermsAggregationBuilder::new,
                RareTermsAggregationBuilder.PARSER)
                    .addResultReader(StringRareTerms.NAME, StringRareTerms::new)
                    .addResultReader(UnmappedRareTerms.NAME, UnmappedRareTerms::new)
                    .addResultReader(LongRareTerms.NAME, LongRareTerms::new));
        registerAggregation(new AggregationSpec(SignificantTermsAggregationBuilder.NAME, SignificantTermsAggregationBuilder::new,
                SignificantTermsAggregationBuilder::parse)
                    .addResultReader(SignificantStringTerms.NAME, SignificantStringTerms::new)
                    .addResultReader(SignificantLongTerms.NAME, SignificantLongTerms::new)
                    .addResultReader(UnmappedSignificantTerms.NAME, UnmappedSignificantTerms::new));
        registerAggregation(new AggregationSpec(SignificantTextAggregationBuilder.NAME, SignificantTextAggregationBuilder::new,
                SignificantTextAggregationBuilder::parse));
        registerAggregation(new AggregationSpec(RangeAggregationBuilder.NAME, RangeAggregationBuilder::new,
                RangeAggregationBuilder.PARSER).addResultReader(InternalRange::new));
        registerAggregation(new AggregationSpec(DateRangeAggregationBuilder.NAME, DateRangeAggregationBuilder::new,
                DateRangeAggregationBuilder.PARSER).addResultReader(InternalDateRange::new));
        registerAggregation(new AggregationSpec(IpRangeAggregationBuilder.NAME, IpRangeAggregationBuilder::new,
                IpRangeAggregationBuilder.PARSER).addResultReader(InternalBinaryRange::new));
        registerAggregation(new AggregationSpec(HistogramAggregationBuilder.NAME, HistogramAggregationBuilder::new,
                HistogramAggregationBuilder.PARSER).addResultReader(InternalHistogram::new));
        registerAggregation(new AggregationSpec(DateHistogramAggregationBuilder.NAME, DateHistogramAggregationBuilder::new,
                DateHistogramAggregationBuilder.PARSER).addResultReader(InternalDateHistogram::new));
        registerAggregation(new AggregationSpec(AutoDateHistogramAggregationBuilder.NAME, AutoDateHistogramAggregationBuilder::new,
                AutoDateHistogramAggregationBuilder.PARSER).addResultReader(InternalAutoDateHistogram::new));
        registerAggregation(new AggregationSpec(GeoDistanceAggregationBuilder.NAME, GeoDistanceAggregationBuilder::new,
                GeoDistanceAggregationBuilder::parse).addResultReader(InternalGeoDistance::new));
        registerAggregation(new AggregationSpec(GeoHashGridAggregationBuilder.NAME, GeoHashGridAggregationBuilder::new,
                GeoHashGridAggregationBuilder.PARSER).addResultReader(InternalGeoHashGrid::new));
        registerAggregation(new AggregationSpec(GeoTileGridAggregationBuilder.NAME, GeoTileGridAggregationBuilder::new,
                GeoTileGridAggregationBuilder.PARSER).addResultReader(InternalGeoTileGrid::new));
        registerAggregation(new AggregationSpec(NestedAggregationBuilder.NAME, NestedAggregationBuilder::new,
                NestedAggregationBuilder::parse).addResultReader(InternalNested::new));
        registerAggregation(new AggregationSpec(ReverseNestedAggregationBuilder.NAME, ReverseNestedAggregationBuilder::new,
                ReverseNestedAggregationBuilder::parse).addResultReader(InternalReverseNested::new));
        registerAggregation(new AggregationSpec(TopHitsAggregationBuilder.NAME, TopHitsAggregationBuilder::new,
                TopHitsAggregationBuilder::parse).addResultReader(InternalTopHits::new));
        registerAggregation(new AggregationSpec(GeoBoundsAggregationBuilder.NAME, GeoBoundsAggregationBuilder::new,
                GeoBoundsAggregationBuilder.PARSER).addResultReader(InternalGeoBounds::new));
        registerAggregation(new AggregationSpec(GeoCentroidAggregationBuilder.NAME, GeoCentroidAggregationBuilder::new,
                GeoCentroidAggregationBuilder.PARSER).addResultReader(InternalGeoCentroid::new));
        registerAggregation(new AggregationSpec(ScriptedMetricAggregationBuilder.NAME, ScriptedMetricAggregationBuilder::new,
                ScriptedMetricAggregationBuilder.PARSER).addResultReader(InternalScriptedMetric::new));
        registerAggregation((new AggregationSpec(CompositeAggregationBuilder.NAME, CompositeAggregationBuilder::new,
                CompositeAggregationBuilder.PARSER).addResultReader(InternalComposite::new)));
        registerFromPlugin(plugins, SearchPlugin::getAggregations, this::registerAggregation);
    }

    private void registerAggregation(AggregationSpec spec) {
        if (false == transportClient) {
            namedXContents.add(new NamedXContentRegistry.Entry(BaseAggregationBuilder.class, spec.getName(), (p, c) -> {
                String name = (String) c;
                return spec.getParser().parse(p, name);
            }));
        }
        namedWriteables.add(
                new NamedWriteableRegistry.Entry(AggregationBuilder.class, spec.getName().getPreferredName(), spec.getReader()));
        for (Map.Entry<String, Writeable.Reader<? extends InternalAggregation>> t : spec.getResultReaders().entrySet()) {
            String writeableName = t.getKey();
            Writeable.Reader<? extends InternalAggregation> internalReader = t.getValue();
            namedWriteables.add(new NamedWriteableRegistry.Entry(InternalAggregation.class, writeableName, internalReader));
        }
    }

    private void registerPipelineAggregations(List<SearchPlugin> plugins) {
        registerPipelineAggregation(new PipelineAggregationSpec(
                DerivativePipelineAggregationBuilder.NAME,
                DerivativePipelineAggregationBuilder::new,
                DerivativePipelineAggregator::new,
                DerivativePipelineAggregationBuilder::parse)
                    .addResultReader(InternalDerivative::new));
        registerPipelineAggregation(new PipelineAggregationSpec(
                MaxBucketPipelineAggregationBuilder.NAME,
                MaxBucketPipelineAggregationBuilder::new,
                MaxBucketPipelineAggregator::new,
                MaxBucketPipelineAggregationBuilder.PARSER)
                    // This bucket is used by many pipeline aggreations.
                    .addResultReader(InternalBucketMetricValue.NAME, InternalBucketMetricValue::new));
        registerPipelineAggregation(new PipelineAggregationSpec(
                MinBucketPipelineAggregationBuilder.NAME,
                MinBucketPipelineAggregationBuilder::new,
                MinBucketPipelineAggregator::new,
                MinBucketPipelineAggregationBuilder.PARSER)
                    /* Uses InternalBucketMetricValue */);
        registerPipelineAggregation(new PipelineAggregationSpec(
                AvgBucketPipelineAggregationBuilder.NAME,
                AvgBucketPipelineAggregationBuilder::new,
                AvgBucketPipelineAggregator::new,
                AvgBucketPipelineAggregationBuilder.PARSER)
                    // This bucket is used by many pipeline aggreations.
                    .addResultReader(InternalSimpleValue.NAME, InternalSimpleValue::new));
        registerPipelineAggregation(new PipelineAggregationSpec(
                SumBucketPipelineAggregationBuilder.NAME,
                SumBucketPipelineAggregationBuilder::new,
                SumBucketPipelineAggregator::new,
                SumBucketPipelineAggregationBuilder.PARSER)
                    /* Uses InternalSimpleValue */);
        registerPipelineAggregation(new PipelineAggregationSpec(
                StatsBucketPipelineAggregationBuilder.NAME,
                StatsBucketPipelineAggregationBuilder::new,
                StatsBucketPipelineAggregator::new,
                StatsBucketPipelineAggregationBuilder.PARSER)
                    .addResultReader(InternalStatsBucket::new));
        registerPipelineAggregation(new PipelineAggregationSpec(
                ExtendedStatsBucketPipelineAggregationBuilder.NAME,
                ExtendedStatsBucketPipelineAggregationBuilder::new,
                ExtendedStatsBucketPipelineAggregator::new,
                new ExtendedStatsBucketParser())
                    .addResultReader(InternalExtendedStatsBucket::new));
        registerPipelineAggregation(new PipelineAggregationSpec(
                PercentilesBucketPipelineAggregationBuilder.NAME,
                PercentilesBucketPipelineAggregationBuilder::new,
                PercentilesBucketPipelineAggregator::new,
                PercentilesBucketPipelineAggregationBuilder.PARSER)
                    .addResultReader(InternalPercentilesBucket::new));
        registerPipelineAggregation(new PipelineAggregationSpec(
                MovAvgPipelineAggregationBuilder.NAME,
                MovAvgPipelineAggregationBuilder::new,
                MovAvgPipelineAggregator::new,
                (XContentParser parser, String name) ->
                    MovAvgPipelineAggregationBuilder.parse(movingAverageModelParserRegistry, name, parser)
                )/* Uses InternalHistogram for buckets */);
        registerPipelineAggregation(new PipelineAggregationSpec(
                CumulativeSumPipelineAggregationBuilder.NAME,
                CumulativeSumPipelineAggregationBuilder::new,
                CumulativeSumPipelineAggregator::new,
                CumulativeSumPipelineAggregationBuilder::parse));
        registerPipelineAggregation(new PipelineAggregationSpec(
                BucketScriptPipelineAggregationBuilder.NAME,
                BucketScriptPipelineAggregationBuilder::new,
                BucketScriptPipelineAggregator::new,
                BucketScriptPipelineAggregationBuilder.PARSER));
        registerPipelineAggregation(new PipelineAggregationSpec(
                BucketSelectorPipelineAggregationBuilder.NAME,
                BucketSelectorPipelineAggregationBuilder::new,
                BucketSelectorPipelineAggregator::new,
                BucketSelectorPipelineAggregationBuilder::parse));
        registerPipelineAggregation(new PipelineAggregationSpec(
                BucketSortPipelineAggregationBuilder.NAME,
                BucketSortPipelineAggregationBuilder::new,
                BucketSortPipelineAggregator::new,
                BucketSortPipelineAggregationBuilder::parse));
        registerPipelineAggregation(new PipelineAggregationSpec(
                SerialDiffPipelineAggregationBuilder.NAME,
                SerialDiffPipelineAggregationBuilder::new,
                SerialDiffPipelineAggregator::new,
                SerialDiffPipelineAggregationBuilder::parse));
        registerPipelineAggregation(new PipelineAggregationSpec(
                MovFnPipelineAggregationBuilder.NAME,
                MovFnPipelineAggregationBuilder::new,
                MovFnPipelineAggregator::new,
                MovFnPipelineAggregationBuilder.PARSER));

        registerFromPlugin(plugins, SearchPlugin::getPipelineAggregations, this::registerPipelineAggregation);
    }

    private void registerPipelineAggregation(PipelineAggregationSpec spec) {
        if (false == transportClient) {
            namedXContents.add(new NamedXContentRegistry.Entry(BaseAggregationBuilder.class, spec.getName(),
                    (p, c) -> spec.getParser().parse(p, (String) c)));
        }
        namedWriteables.add(
                new NamedWriteableRegistry.Entry(PipelineAggregationBuilder.class, spec.getName().getPreferredName(), spec.getReader()));
        namedWriteables.add(
                new NamedWriteableRegistry.Entry(PipelineAggregator.class, spec.getName().getPreferredName(), spec.getAggregatorReader()));
        for (Map.Entry<String, Writeable.Reader<? extends InternalAggregation>> resultReader : spec.getResultReaders().entrySet()) {
            namedWriteables
                    .add(new NamedWriteableRegistry.Entry(InternalAggregation.class, resultReader.getKey(), resultReader.getValue()));
        }
    }

    private void registerShapes() {
        if (ShapesAvailability.JTS_AVAILABLE && ShapesAvailability.SPATIAL4J_AVAILABLE) {
            namedWriteables.addAll(GeoShapeType.getShapeWriteables());
        }
    }

    private void registerRescorers(List<SearchPlugin> plugins) {
        registerRescorer(new RescorerSpec<>(QueryRescorerBuilder.NAME, QueryRescorerBuilder::new, QueryRescorerBuilder::fromXContent));
        registerFromPlugin(plugins, SearchPlugin::getRescorers, this::registerRescorer);
    }

    private void registerRescorer(RescorerSpec<?> spec) {
        if (false == transportClient) {
            namedXContents.add(new NamedXContentRegistry.Entry(RescorerBuilder.class, spec.getName(), (p, c) -> spec.getParser().apply(p)));
        }
        namedWriteables.add(new NamedWriteableRegistry.Entry(RescorerBuilder.class, spec.getName().getPreferredName(), spec.getReader()));
    }

    private void registerSorts() {
        namedWriteables.add(new NamedWriteableRegistry.Entry(SortBuilder.class, GeoDistanceSortBuilder.NAME, GeoDistanceSortBuilder::new));
        namedWriteables.add(new NamedWriteableRegistry.Entry(SortBuilder.class, ScoreSortBuilder.NAME, ScoreSortBuilder::new));
        namedWriteables.add(new NamedWriteableRegistry.Entry(SortBuilder.class, ScriptSortBuilder.NAME, ScriptSortBuilder::new));
        namedWriteables.add(new NamedWriteableRegistry.Entry(SortBuilder.class, FieldSortBuilder.NAME, FieldSortBuilder::new));
    }

    private <T> void registerFromPlugin(List<SearchPlugin> plugins, Function<SearchPlugin, List<T>> producer, Consumer<T> consumer) {
        for (SearchPlugin plugin : plugins) {
            for (T t : producer.apply(plugin)) {
                consumer.accept(t);
            }
        }
    }

    public static void registerSmoothingModels(List<Entry> namedWriteables) {
        namedWriteables.add(new NamedWriteableRegistry.Entry(SmoothingModel.class, Laplace.NAME, Laplace::new));
        namedWriteables.add(new NamedWriteableRegistry.Entry(SmoothingModel.class, LinearInterpolation.NAME, LinearInterpolation::new));
        namedWriteables.add(new NamedWriteableRegistry.Entry(SmoothingModel.class, StupidBackoff.NAME, StupidBackoff::new));
    }

    private void registerSuggesters(List<SearchPlugin> plugins) {
        registerSmoothingModels(namedWriteables);

        registerSuggester(new SuggesterSpec<>(TermSuggestionBuilder.SUGGESTION_NAME,
            TermSuggestionBuilder::new, TermSuggestionBuilder::fromXContent, TermSuggestion::new));

        registerSuggester(new SuggesterSpec<>(PhraseSuggestionBuilder.SUGGESTION_NAME,
            PhraseSuggestionBuilder::new, PhraseSuggestionBuilder::fromXContent, PhraseSuggestion::new));

        registerSuggester(new SuggesterSpec<>(CompletionSuggestionBuilder.SUGGESTION_NAME,
            CompletionSuggestionBuilder::new, CompletionSuggestionBuilder::fromXContent, CompletionSuggestion::new));

        registerFromPlugin(plugins, SearchPlugin::getSuggesters, this::registerSuggester);
    }

    private void registerSuggester(SuggesterSpec<?> suggester) {
        namedWriteables.add(new NamedWriteableRegistry.Entry(
                SuggestionBuilder.class, suggester.getName().getPreferredName(), suggester.getReader()));
        namedXContents.add(new NamedXContentRegistry.Entry(SuggestionBuilder.class, suggester.getName(),
                suggester.getParser()));

        namedWriteables.add(new NamedWriteableRegistry.Entry(
            Suggest.Suggestion.class, suggester.getName().getPreferredName(), suggester.getSuggestionReader()
        ));
    }

    private Map<String, Highlighter> setupHighlighters(Settings settings, List<SearchPlugin> plugins) {
        NamedRegistry<Highlighter> highlighters = new NamedRegistry<>("highlighter");
        highlighters.register("fvh",  new FastVectorHighlighter(settings));
        highlighters.register("plain", new PlainHighlighter());
        highlighters.register("unified", new UnifiedHighlighter());
        highlighters.extractAndRegister(plugins, SearchPlugin::getHighlighters);

        return unmodifiableMap(highlighters.getRegistry());
    }

    private void registerScoreFunctions(List<SearchPlugin> plugins) {
        // ScriptScoreFunctionBuilder has it own named writable because of a new script_score query
        namedWriteables.add(new NamedWriteableRegistry.Entry(
            ScriptScoreFunctionBuilder.class, ScriptScoreFunctionBuilder.NAME,  ScriptScoreFunctionBuilder::new));
        registerScoreFunction(new ScoreFunctionSpec<>(ScriptScoreFunctionBuilder.NAME, ScriptScoreFunctionBuilder::new,
                ScriptScoreFunctionBuilder::fromXContent));

        registerScoreFunction(
                new ScoreFunctionSpec<>(GaussDecayFunctionBuilder.NAME, GaussDecayFunctionBuilder::new, GaussDecayFunctionBuilder.PARSER));
        registerScoreFunction(new ScoreFunctionSpec<>(LinearDecayFunctionBuilder.NAME, LinearDecayFunctionBuilder::new,
                LinearDecayFunctionBuilder.PARSER));
        registerScoreFunction(new ScoreFunctionSpec<>(ExponentialDecayFunctionBuilder.NAME, ExponentialDecayFunctionBuilder::new,
                ExponentialDecayFunctionBuilder.PARSER));
        registerScoreFunction(new ScoreFunctionSpec<>(RandomScoreFunctionBuilder.NAME, RandomScoreFunctionBuilder::new,
                RandomScoreFunctionBuilder::fromXContent));
        registerScoreFunction(new ScoreFunctionSpec<>(FieldValueFactorFunctionBuilder.NAME, FieldValueFactorFunctionBuilder::new,
                FieldValueFactorFunctionBuilder::fromXContent));

        //weight doesn't have its own parser, so every function supports it out of the box.
        //Can be a single function too when not associated to any other function, which is why it needs to be registered manually here.
        namedWriteables.add(new NamedWriteableRegistry.Entry(ScoreFunctionBuilder.class, WeightBuilder.NAME, WeightBuilder::new));

        registerFromPlugin(plugins, SearchPlugin::getScoreFunctions, this::registerScoreFunction);
    }

    private void registerScoreFunction(ScoreFunctionSpec<?> scoreFunction) {
        namedWriteables.add(new NamedWriteableRegistry.Entry(
                ScoreFunctionBuilder.class, scoreFunction.getName().getPreferredName(), scoreFunction.getReader()));
        // TODO remove funky contexts
        namedXContents.add(new NamedXContentRegistry.Entry(
                ScoreFunctionBuilder.class, scoreFunction.getName(),
                (XContentParser p, Object c) -> scoreFunction.getParser().fromXContent(p)));
    }

    private void registerValueFormats() {
        registerValueFormat(DocValueFormat.BOOLEAN.getWriteableName(), in -> DocValueFormat.BOOLEAN);
        registerValueFormat(DocValueFormat.DateTime.NAME, DocValueFormat.DateTime::new);
        registerValueFormat(DocValueFormat.Decimal.NAME, DocValueFormat.Decimal::new);
        registerValueFormat(DocValueFormat.GEOHASH.getWriteableName(), in -> DocValueFormat.GEOHASH);
        registerValueFormat(DocValueFormat.GEOTILE.getWriteableName(), in -> DocValueFormat.GEOTILE);
        registerValueFormat(DocValueFormat.IP.getWriteableName(), in -> DocValueFormat.IP);
        registerValueFormat(DocValueFormat.RAW.getWriteableName(), in -> DocValueFormat.RAW);
        registerValueFormat(DocValueFormat.BINARY.getWriteableName(), in -> DocValueFormat.BINARY);
    }

    /**
     * Register a new ValueFormat.
     */
    private void registerValueFormat(String name, Writeable.Reader<? extends DocValueFormat> reader) {
        namedWriteables.add(new NamedWriteableRegistry.Entry(DocValueFormat.class, name, reader));
    }

    private void registerSignificanceHeuristics(List<SearchPlugin> plugins) {
        registerSignificanceHeuristic(new SignificanceHeuristicSpec<>(ChiSquare.NAME, ChiSquare::new, ChiSquare.PARSER));
        registerSignificanceHeuristic(new SignificanceHeuristicSpec<>(GND.NAME, GND::new, GND.PARSER));
        registerSignificanceHeuristic(new SignificanceHeuristicSpec<>(JLHScore.NAME, JLHScore::new, JLHScore.PARSER));
        registerSignificanceHeuristic(new SignificanceHeuristicSpec<>(
            MutualInformation.NAME, MutualInformation::new, MutualInformation.PARSER));
        registerSignificanceHeuristic(new SignificanceHeuristicSpec<>(PercentageScore.NAME, PercentageScore::new, PercentageScore.PARSER));
        registerSignificanceHeuristic(new SignificanceHeuristicSpec<>(ScriptHeuristic.NAME, ScriptHeuristic::new, ScriptHeuristic.PARSER));

        registerFromPlugin(plugins, SearchPlugin::getSignificanceHeuristics, this::registerSignificanceHeuristic);
    }

    private <T extends SignificanceHeuristic> void registerSignificanceHeuristic(SignificanceHeuristicSpec<?> spec) {
        namedXContents.add(new NamedXContentRegistry.Entry(
            SignificanceHeuristic.class, spec.getName(), p -> spec.getParser().apply(p, null)));
        namedWriteables.add(new NamedWriteableRegistry.Entry(
            SignificanceHeuristic.class, spec.getName().getPreferredName(), spec.getReader()));
    }

    private void registerMovingAverageModels(List<SearchPlugin> plugins) {
        registerMovingAverageModel(new SearchExtensionSpec<>(SimpleModel.NAME, SimpleModel::new, SimpleModel.PARSER));
        registerMovingAverageModel(new SearchExtensionSpec<>(LinearModel.NAME, LinearModel::new, LinearModel.PARSER));
        registerMovingAverageModel(new SearchExtensionSpec<>(EwmaModel.NAME, EwmaModel::new, EwmaModel.PARSER));
        registerMovingAverageModel(new SearchExtensionSpec<>(HoltLinearModel.NAME, HoltLinearModel::new, HoltLinearModel.PARSER));
        registerMovingAverageModel(new SearchExtensionSpec<>(HoltWintersModel.NAME, HoltWintersModel::new, HoltWintersModel.PARSER));

        registerFromPlugin(plugins, SearchPlugin::getMovingAverageModels, this::registerMovingAverageModel);
    }

    private void registerMovingAverageModel(SearchExtensionSpec<MovAvgModel, MovAvgModel.AbstractModelParser> movAvgModel) {
        movingAverageModelParserRegistry.register(movAvgModel.getParser(), movAvgModel.getName());
        namedWriteables.add(
                new NamedWriteableRegistry.Entry(MovAvgModel.class, movAvgModel.getName().getPreferredName(), movAvgModel.getReader()));
    }

    private void registerFetchSubPhases(List<SearchPlugin> plugins) {
        registerFetchSubPhase(new ExplainPhase());
        registerFetchSubPhase(new FetchDocValuesPhase());
        registerFetchSubPhase(new ScriptFieldsPhase());
        registerFetchSubPhase(new FetchSourcePhase());
        registerFetchSubPhase(new FetchVersionPhase());
        registerFetchSubPhase(new SeqNoPrimaryTermPhase());
        registerFetchSubPhase(new MatchedQueriesPhase());
        registerFetchSubPhase(new HighlightPhase(highlighters));
        registerFetchSubPhase(new FetchScorePhase());

        FetchPhaseConstructionContext context = new FetchPhaseConstructionContext(highlighters);
        registerFromPlugin(plugins, p -> p.getFetchSubPhases(context), this::registerFetchSubPhase);
    }

    private void registerSearchExts(List<SearchPlugin> plugins) {
        registerFromPlugin(plugins, SearchPlugin::getSearchExts, this::registerSearchExt);
    }

    private void registerSearchExt(SearchExtSpec<?> spec) {
        namedXContents.add(new NamedXContentRegistry.Entry(SearchExtBuilder.class, spec.getName(), spec.getParser()));
        namedWriteables.add(new NamedWriteableRegistry.Entry(SearchExtBuilder.class, spec.getName().getPreferredName(), spec.getReader()));
    }

    private void registerFetchSubPhase(FetchSubPhase subPhase) {
        Class<?> subPhaseClass = subPhase.getClass();
        if (fetchSubPhases.stream().anyMatch(p -> p.getClass().equals(subPhaseClass))) {
            throw new IllegalArgumentException("FetchSubPhase [" + subPhaseClass + "] already registered");
        }
        fetchSubPhases.add(requireNonNull(subPhase, "FetchSubPhase must not be null"));
    }

    private void registerQueryParsers(List<SearchPlugin> plugins) {
        registerQuery(new QuerySpec<>(MatchQueryBuilder.NAME, MatchQueryBuilder::new, MatchQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(MatchPhraseQueryBuilder.NAME, MatchPhraseQueryBuilder::new, MatchPhraseQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(MatchPhrasePrefixQueryBuilder.NAME, MatchPhrasePrefixQueryBuilder::new,
                MatchPhrasePrefixQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(MultiMatchQueryBuilder.NAME, MultiMatchQueryBuilder::new, MultiMatchQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(NestedQueryBuilder.NAME, NestedQueryBuilder::new, NestedQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(DisMaxQueryBuilder.NAME, DisMaxQueryBuilder::new, DisMaxQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(IdsQueryBuilder.NAME, IdsQueryBuilder::new, IdsQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(MatchAllQueryBuilder.NAME, MatchAllQueryBuilder::new, MatchAllQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(QueryStringQueryBuilder.NAME, QueryStringQueryBuilder::new, QueryStringQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(BoostingQueryBuilder.NAME, BoostingQueryBuilder::new, BoostingQueryBuilder::fromXContent));
        BooleanQuery.setMaxClauseCount(INDICES_MAX_CLAUSE_COUNT_SETTING.get(settings));
        registerQuery(new QuerySpec<>(BoolQueryBuilder.NAME, BoolQueryBuilder::new, BoolQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(TermQueryBuilder.NAME, TermQueryBuilder::new, TermQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(TermsQueryBuilder.NAME, TermsQueryBuilder::new, TermsQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(FuzzyQueryBuilder.NAME, FuzzyQueryBuilder::new, FuzzyQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(RegexpQueryBuilder.NAME, RegexpQueryBuilder::new, RegexpQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(RangeQueryBuilder.NAME, RangeQueryBuilder::new, RangeQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(PrefixQueryBuilder.NAME, PrefixQueryBuilder::new, PrefixQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(WildcardQueryBuilder.NAME, WildcardQueryBuilder::new, WildcardQueryBuilder::fromXContent));
        registerQuery(
                new QuerySpec<>(ConstantScoreQueryBuilder.NAME, ConstantScoreQueryBuilder::new, ConstantScoreQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(SpanTermQueryBuilder.NAME, SpanTermQueryBuilder::new, SpanTermQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(SpanNotQueryBuilder.NAME, SpanNotQueryBuilder::new, SpanNotQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(SpanWithinQueryBuilder.NAME, SpanWithinQueryBuilder::new, SpanWithinQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(SpanContainingQueryBuilder.NAME, SpanContainingQueryBuilder::new,
                SpanContainingQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(FieldMaskingSpanQueryBuilder.NAME, FieldMaskingSpanQueryBuilder::new,
                FieldMaskingSpanQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(SpanFirstQueryBuilder.NAME, SpanFirstQueryBuilder::new, SpanFirstQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(SpanNearQueryBuilder.NAME, SpanNearQueryBuilder::new, SpanNearQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(SpanGapQueryBuilder.NAME, SpanGapQueryBuilder::new, SpanGapQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(SpanOrQueryBuilder.NAME, SpanOrQueryBuilder::new, SpanOrQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(MoreLikeThisQueryBuilder.NAME, MoreLikeThisQueryBuilder::new,
                MoreLikeThisQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(WrapperQueryBuilder.NAME, WrapperQueryBuilder::new, WrapperQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(new ParseField(CommonTermsQueryBuilder.NAME).withAllDeprecated(COMMON_TERMS_QUERY_DEPRECATION_MSG),
                CommonTermsQueryBuilder::new, CommonTermsQueryBuilder::fromXContent));
        registerQuery(
                new QuerySpec<>(SpanMultiTermQueryBuilder.NAME, SpanMultiTermQueryBuilder::new, SpanMultiTermQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(FunctionScoreQueryBuilder.NAME, FunctionScoreQueryBuilder::new,
                FunctionScoreQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(ScriptScoreQueryBuilder.NAME, ScriptScoreQueryBuilder::new, ScriptScoreQueryBuilder::fromXContent));
        registerQuery(
                new QuerySpec<>(SimpleQueryStringBuilder.NAME, SimpleQueryStringBuilder::new, SimpleQueryStringBuilder::fromXContent));
        registerQuery(new QuerySpec<>(TypeQueryBuilder.NAME, TypeQueryBuilder::new, TypeQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(ScriptQueryBuilder.NAME, ScriptQueryBuilder::new, ScriptQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(GeoDistanceQueryBuilder.NAME, GeoDistanceQueryBuilder::new, GeoDistanceQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(GeoBoundingBoxQueryBuilder.NAME, GeoBoundingBoxQueryBuilder::new,
                GeoBoundingBoxQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(GeoPolygonQueryBuilder.NAME, GeoPolygonQueryBuilder::new, GeoPolygonQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(ExistsQueryBuilder.NAME, ExistsQueryBuilder::new, ExistsQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(MatchNoneQueryBuilder.NAME, MatchNoneQueryBuilder::new, MatchNoneQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(TermsSetQueryBuilder.NAME, TermsSetQueryBuilder::new, TermsSetQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(IntervalQueryBuilder.NAME, IntervalQueryBuilder::new, IntervalQueryBuilder::fromXContent));
        registerQuery(new QuerySpec<>(DistanceFeatureQueryBuilder.NAME, DistanceFeatureQueryBuilder::new,
            DistanceFeatureQueryBuilder::fromXContent));
        registerQuery(
            new QuerySpec<>(MatchBoolPrefixQueryBuilder.NAME, MatchBoolPrefixQueryBuilder::new, MatchBoolPrefixQueryBuilder::fromXContent));

        if (ShapesAvailability.JTS_AVAILABLE && ShapesAvailability.SPATIAL4J_AVAILABLE) {
            registerQuery(new QuerySpec<>(GeoShapeQueryBuilder.NAME, GeoShapeQueryBuilder::new, GeoShapeQueryBuilder::fromXContent));
        }

        registerFromPlugin(plugins, SearchPlugin::getQueries, this::registerQuery);
    }

    private void registerIntervalsSourceProviders() {
        namedWriteables.addAll(getIntervalsSourceProviderNamedWritables());
    }

    public static List<NamedWriteableRegistry.Entry> getIntervalsSourceProviderNamedWritables() {
        return unmodifiableList(Arrays.asList(
                new NamedWriteableRegistry.Entry(IntervalsSourceProvider.class, IntervalsSourceProvider.Match.NAME,
                        IntervalsSourceProvider.Match::new),
                new NamedWriteableRegistry.Entry(IntervalsSourceProvider.class, IntervalsSourceProvider.Combine.NAME,
                        IntervalsSourceProvider.Combine::new),
                new NamedWriteableRegistry.Entry(IntervalsSourceProvider.class, IntervalsSourceProvider.Disjunction.NAME,
                        IntervalsSourceProvider.Disjunction::new),
                new NamedWriteableRegistry.Entry(IntervalsSourceProvider.class, IntervalsSourceProvider.Prefix.NAME,
                        IntervalsSourceProvider.Prefix::new),
                new NamedWriteableRegistry.Entry(IntervalsSourceProvider.class, IntervalsSourceProvider.Wildcard.NAME,
                        IntervalsSourceProvider.Wildcard::new),
                new NamedWriteableRegistry.Entry(IntervalsSourceProvider.class, IntervalsSourceProvider.Fuzzy.NAME,
                        IntervalsSourceProvider.Fuzzy::new)));
    }

    private void registerQuery(QuerySpec<?> spec) {
        namedWriteables.add(new NamedWriteableRegistry.Entry(QueryBuilder.class, spec.getName().getPreferredName(), spec.getReader()));
        namedXContents.add(new NamedXContentRegistry.Entry(QueryBuilder.class, spec.getName(),
                (p, c) -> spec.getParser().fromXContent(p)));
    }

    public FetchPhase getFetchPhase() {
        return new FetchPhase(fetchSubPhases);
    }
}
