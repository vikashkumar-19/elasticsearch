/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.analytics.topmetrics;

import org.elasticsearch.client.analytics.ParsedTopMetrics;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.Writeable.Reader;
import org.elasticsearch.common.time.DateFormatter;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.index.mapper.DateFieldMapper;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.ParsedAggregation;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.search.sort.SortValue;
import org.elasticsearch.test.InternalAggregationTestCase;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;

public class InternalTopMetricsTests extends InternalAggregationTestCase<InternalTopMetrics> {
    /**
     * Sort order to use for randomly generated instances. This is fixed
     * for each test method so that randomly generated instances can be
     * merged. If it weren't fixed {@link InternalAggregationTestCase#testReduceRandom()}
     * would fail because the instances that it attempts to reduce don't
     * have their results in the same order.
     */
    private final SortOrder sortOrder = randomFrom(SortOrder.values());
    private final InternalTopMetrics.MetricValue metricOneDouble =
            new InternalTopMetrics.MetricValue(DocValueFormat.RAW, SortValue.from(1.0));
    private final InternalTopMetrics.MetricValue metricOneLong =
            new InternalTopMetrics.MetricValue(DocValueFormat.RAW, SortValue.from(1));

    public void testEmptyIsNotMapped() {
        InternalTopMetrics empty = InternalTopMetrics.buildEmptyAggregation(
                randomAlphaOfLength(5), randomMetricNames(between(1, 5)), emptyList(), null);
        assertFalse(empty.isMapped());
    }

    public void testNonEmptyIsMapped() {
        InternalTopMetrics nonEmpty = randomValueOtherThanMany(i -> i.getTopMetrics().isEmpty(), this::createTestInstance);
        assertTrue(nonEmpty.isMapped());
    }

    public void testToXContentDoubleSortValue() throws IOException {
        List<InternalTopMetrics.TopMetric> top = singletonList(
                new InternalTopMetrics.TopMetric(DocValueFormat.RAW, SortValue.from(1.0), singletonList(metricOneDouble)));
        InternalTopMetrics tm = new InternalTopMetrics("test", sortOrder, singletonList("test"), 1, top, emptyList(), null);
        assertThat(Strings.toString(tm, true, true), equalTo(
                "{\n" +
                "  \"test\" : {\n" +
                "    \"top\" : [\n" +
                "      {\n" +
                "        \"sort\" : [\n" +
                "          1.0\n" +
                "        ],\n" +
                "        \"metrics\" : {\n" +
                "          \"test\" : 1.0\n" +
                "        }\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "}"));
    }

    public void testToXContentDateSortValue() throws IOException {
        SortValue sortValue = SortValue.from(ZonedDateTime.parse("2007-12-03T10:15:30Z").toInstant().toEpochMilli());
        List<InternalTopMetrics.TopMetric> top = singletonList(new InternalTopMetrics.TopMetric(
                strictDateTime(), sortValue, singletonList(metricOneDouble)));
        InternalTopMetrics tm = new InternalTopMetrics("test", sortOrder, singletonList("test"), 1, top, emptyList(), null);
        assertThat(Strings.toString(tm, true, true), equalTo(
                "{\n" +
                "  \"test\" : {\n" +
                "    \"top\" : [\n" +
                "      {\n" +
                "        \"sort\" : [\n" +
                "          \"2007-12-03T10:15:30.000Z\"\n" +
                "        ],\n" +
                "        \"metrics\" : {\n" +
                "          \"test\" : 1.0\n" +
                "        }\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "}"));
    }

    public void testToXContentLongMetricValue() throws IOException {
        List<InternalTopMetrics.TopMetric> top = singletonList(
                new InternalTopMetrics.TopMetric(DocValueFormat.RAW, SortValue.from(1.0), singletonList(metricOneLong)));
        InternalTopMetrics tm = new InternalTopMetrics("test", sortOrder, singletonList("test"), 1, top, emptyList(), null);
        assertThat(Strings.toString(tm, true, true), equalTo(
                "{\n" +
                "  \"test\" : {\n" +
                "    \"top\" : [\n" +
                "      {\n" +
                "        \"sort\" : [\n" +
                "          1.0\n" +
                "        ],\n" +
                "        \"metrics\" : {\n" +
                "          \"test\" : 1\n" +
                "        }\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "}"));
    }

    public void testToXContentDateMetricValue() throws IOException {
        InternalTopMetrics.MetricValue metricValue = new InternalTopMetrics.MetricValue(
                strictDateTime(), SortValue.from(ZonedDateTime.parse("2007-12-03T10:15:30Z").toInstant().toEpochMilli()));
        List<InternalTopMetrics.TopMetric> top = singletonList(
                new InternalTopMetrics.TopMetric(DocValueFormat.RAW, SortValue.from(1.0), singletonList(metricValue)));
        InternalTopMetrics tm = new InternalTopMetrics("test", sortOrder, singletonList("test"), 1, top, emptyList(), null);
        assertThat(Strings.toString(tm, true, true), equalTo(
                "{\n" +
                "  \"test\" : {\n" +
                "    \"top\" : [\n" +
                "      {\n" +
                "        \"sort\" : [\n" +
                "          1.0\n" +
                "        ],\n" +
                "        \"metrics\" : {\n" +
                "          \"test\" : \"2007-12-03T10:15:30.000Z\"\n" +
                "        }\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "}"));
    }

    public void testToXContentManyMetrics() throws IOException {
        List<InternalTopMetrics.TopMetric> top = singletonList(new InternalTopMetrics.TopMetric(
                DocValueFormat.RAW, SortValue.from(1.0), Arrays.asList(metricOneDouble, metricOneLong, metricOneDouble)));
        InternalTopMetrics tm = new InternalTopMetrics("test", sortOrder, Arrays.asList("foo", "bar", "baz"), 1, top, emptyList(), null);
        assertThat(Strings.toString(tm, true, true), equalTo(
                "{\n" +
                "  \"test\" : {\n" +
                "    \"top\" : [\n" +
                "      {\n" +
                "        \"sort\" : [\n" +
                "          1.0\n" +
                "        ],\n" +
                "        \"metrics\" : {\n" +
                "          \"foo\" : 1.0,\n" +
                "          \"bar\" : 1,\n" +
                "          \"baz\" : 1.0\n" +
                "        }\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "}"));
    }

    public void testToXContentManyTopMetrics() throws IOException {
        List<InternalTopMetrics.TopMetric> top = Arrays.asList(
                new InternalTopMetrics.TopMetric(DocValueFormat.RAW, SortValue.from(1.0), singletonList(metricOneDouble)),
                new InternalTopMetrics.TopMetric(DocValueFormat.RAW, SortValue.from(2.0), singletonList(metricOneLong)));
        InternalTopMetrics tm = new InternalTopMetrics("test", sortOrder, singletonList("test"), 2, top, emptyList(), null);
        assertThat(Strings.toString(tm, true, true), equalTo(
                "{\n" +
                "  \"test\" : {\n" +
                "    \"top\" : [\n" +
                "      {\n" +
                "        \"sort\" : [\n" +
                "          1.0\n" +
                "        ],\n" +
                "        \"metrics\" : {\n" +
                "          \"test\" : 1.0\n" +
                "        }\n" +
                "      },\n" +
                "      {\n" +
                "        \"sort\" : [\n" +
                "          2.0\n" +
                "        ],\n" +
                "        \"metrics\" : {\n" +
                "          \"test\" : 1\n" +
                "        }\n" +
                "      }\n" +
                "    ]\n" +
                "  }\n" +
                "}"));
    }

    @Override
    protected List<NamedXContentRegistry.Entry> getNamedXContents() {
        List<NamedXContentRegistry.Entry> result = new ArrayList<>(super.getNamedXContents());
        result.add(new NamedXContentRegistry.Entry(Aggregation.class, new ParseField(TopMetricsAggregationBuilder.NAME),
                (p, c) -> ParsedTopMetrics.PARSER.parse(p, (String) c)));
        return result;
    }

    @Override
    protected InternalTopMetrics createTestInstance(String name, List<PipelineAggregator> pipelineAggregators,
            Map<String, Object> metaData) {
        return createTestInstance(name, pipelineAggregators, metaData, InternalAggregationTestCase::randomNumericDocValueFormat);
    }

    private InternalTopMetrics createTestInstance(String name, List<PipelineAggregator> pipelineAggregators,
            Map<String, Object> metaData, Supplier<DocValueFormat> randomDocValueFormat) {
        int metricCount = between(1, 5);
        List<String> metricNames = randomMetricNames(metricCount);
        int size = between(1, 100);
        List<InternalTopMetrics.TopMetric> topMetrics = randomTopMetrics(randomDocValueFormat, between(0, size), metricCount);
        return new InternalTopMetrics(name, sortOrder, metricNames, size, topMetrics, pipelineAggregators, metaData);
    }

    @Override
    protected InternalTopMetrics mutateInstance(InternalTopMetrics instance) throws IOException {
        String name = instance.getName();
        SortOrder sortOrder = instance.getSortOrder();
        List<String> metricNames = instance.getMetricNames();
        int size = instance.getSize();
        List<InternalTopMetrics.TopMetric> topMetrics = instance.getTopMetrics();
        switch (randomInt(4)) {
        case 0:
            name = randomAlphaOfLength(6);
            break;
        case 1:
            sortOrder = sortOrder == SortOrder.ASC ? SortOrder.DESC : SortOrder.ASC;
            Collections.reverse(topMetrics);
            break;
        case 2:
            metricNames = new ArrayList<>(metricNames);
            metricNames.set(randomInt(metricNames.size() - 1), randomAlphaOfLength(6));
            break;
        case 3:
            size = randomValueOtherThan(size, () -> between(1, 100));
            break;
        case 4:
            int fixedSize = size;
            int fixedMetricsSize = metricNames.size();
            topMetrics = randomValueOtherThan(topMetrics, () -> randomTopMetrics(
                    InternalAggregationTestCase::randomNumericDocValueFormat, between(1, fixedSize), fixedMetricsSize));
            break;
        default:
            throw new IllegalArgumentException("bad mutation");
        }
        return new InternalTopMetrics(name, sortOrder, metricNames, size, topMetrics,
                instance.pipelineAggregators(), instance.getMetaData());
    }

    @Override
    protected Reader<InternalTopMetrics> instanceReader() {
        return InternalTopMetrics::new;
    }

    /**
     * An extra test for parsing dates from xcontent because we can't random
     * into {@link DocValueFormat.DateTime} because it doesn't
     * implement {@link Object#equals(Object)}.
     */
    public void testFromXContentDates() throws IOException {
        InternalTopMetrics aggregation = createTestInstance(
                randomAlphaOfLength(3), emptyList(), emptyMap(), InternalTopMetricsTests::strictDateTime);
        ParsedAggregation parsedAggregation = parseAndAssert(aggregation, randomBoolean(), randomBoolean());
        assertFromXContent(aggregation, parsedAggregation);
    }

    @Override
    protected void assertFromXContent(InternalTopMetrics aggregation, ParsedAggregation parsedAggregation) throws IOException {
        ParsedTopMetrics parsed = (ParsedTopMetrics) parsedAggregation;
        assertThat(parsed.getName(), equalTo(aggregation.getName()));
        assertThat(parsed.getTopMetrics(), hasSize(aggregation.getTopMetrics().size()));
        for (int i = 0; i < parsed.getTopMetrics().size(); i++) {
            ParsedTopMetrics.TopMetrics parsedTop = parsed.getTopMetrics().get(i);
            InternalTopMetrics.TopMetric internalTop = aggregation.getTopMetrics().get(i);
            Object expectedSort = internalTop.getSortFormat() == DocValueFormat.RAW ?
                    internalTop.getSortValue().getKey() : internalTop.getSortValue().format(internalTop.getSortFormat());
            assertThat(parsedTop.getSort(), equalTo(singletonList(expectedSort)));
            assertThat(parsedTop.getMetrics().keySet(), hasSize(aggregation.getMetricNames().size()));
            for (int m = 0; m < aggregation.getMetricNames().size(); m++) {
                String name = aggregation.getMetricNames().get(m);
                InternalTopMetrics.MetricValue value = internalTop.getMetricValues().get(m);
                assertThat(parsedTop.getMetrics(), hasKey(name));
                if (value.getFormat() == DocValueFormat.RAW) {
                    assertThat(parsedTop.getMetrics().get(name), equalTo(value.numberValue()));
                } else {
                    assertThat(parsedTop.getMetrics().get(name), equalTo(value.getValue().format(value.getFormat())));
                }
            }
        }
    }

    @Override
    protected void assertReduced(InternalTopMetrics reduced, List<InternalTopMetrics> inputs) {
        InternalTopMetrics first = inputs.get(0);
        List<InternalTopMetrics.TopMetric> metrics = new ArrayList<>();
        for (InternalTopMetrics input : inputs) {
            metrics.addAll(input.getTopMetrics());
        }
        Collections.sort(metrics, (lhs, rhs) -> first.getSortOrder().reverseMul() * lhs.getSortValue().compareTo(rhs.getSortValue()));
        List<InternalTopMetrics.TopMetric> winners = metrics.size() > first.getSize() ? metrics.subList(0, first.getSize()) : metrics;
        assertThat(reduced.getName(), equalTo(first.getName()));
        assertThat(reduced.getSortOrder(), equalTo(first.getSortOrder()));
        assertThat(reduced.getMetricNames(), equalTo(first.getMetricNames()));
        assertThat(reduced.getTopMetrics(), equalTo(winners));
    }

    private List<InternalTopMetrics.TopMetric> randomTopMetrics(
            Supplier<DocValueFormat> randomDocValueFormat, int length, int metricCount) {
        return IntStream.range(0, length)
                .mapToObj(i -> new InternalTopMetrics.TopMetric(
                        randomDocValueFormat.get(), randomSortValue(), randomMetricValues(randomDocValueFormat, metricCount)
                ))
                .sorted((lhs, rhs) -> sortOrder.reverseMul() * lhs.getSortValue().compareTo(rhs.getSortValue()))
                .collect(toList());
    }

    static List<String> randomMetricNames(int metricCount) {
        Set<String> names = new HashSet<>(metricCount);
        while (names.size() < metricCount) {
            names.add(randomAlphaOfLength(5));
        }
        return new ArrayList<>(names);
    }

    private List<InternalTopMetrics.MetricValue> randomMetricValues(Supplier<DocValueFormat> randomDocValueFormat, int metricCount) {
        return IntStream.range(0, metricCount)
                .mapToObj(i -> new InternalTopMetrics.MetricValue(randomDocValueFormat.get(), randomSortValue()))
                .collect(toList());
    }

    private static DocValueFormat strictDateTime() {
        return new DocValueFormat.DateTime(
            DateFormatter.forPattern("strict_date_time"), ZoneId.of("UTC"), DateFieldMapper.Resolution.MILLISECONDS);
    }


    private static SortValue randomSortValue() {
        if (randomBoolean()) {
            return SortValue.from(randomLong());
        }
        return SortValue.from(randomDouble());
    }

    @Override
    protected Predicate<String> excludePathsFromXContentInsertion() {
        return path -> path.endsWith(".metrics");
    }
}
