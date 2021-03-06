/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.server;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.airlift.units.Duration;
import io.prestosql.Session;
import io.prestosql.execution.SqlQueryExecution;
import io.prestosql.execution.StageState;
import io.prestosql.operator.JoinUtils;
import io.prestosql.spi.QueryId;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.DynamicFilter;
import io.prestosql.spi.predicate.DiscreteValues;
import io.prestosql.spi.predicate.Domain;
import io.prestosql.spi.predicate.Ranges;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.sql.DynamicFilters;
import io.prestosql.sql.analyzer.FeaturesConfig;
import io.prestosql.sql.planner.PlanFragment;
import io.prestosql.sql.planner.SubPlan;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.planner.optimizations.PlanNodeSearcher;
import io.prestosql.sql.planner.plan.DynamicFilterId;
import io.prestosql.sql.planner.plan.JoinNode;
import io.prestosql.sql.planner.plan.PlanNode;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Sets.difference;
import static io.airlift.concurrent.MoreFutures.toCompletableFuture;
import static io.airlift.concurrent.MoreFutures.whenAnyComplete;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.prestosql.spi.connector.DynamicFilter.EMPTY;
import static io.prestosql.spi.predicate.Domain.union;
import static io.prestosql.sql.DynamicFilters.extractDynamicFilters;
import static io.prestosql.sql.planner.ExpressionExtractor.extractExpressions;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;

@ThreadSafe
public class DynamicFilterService
{
    private final Duration dynamicFilteringRefreshInterval;
    private final ScheduledExecutorService collectDynamicFiltersExecutor = newSingleThreadScheduledExecutor(daemonThreadsNamed("DynamicFilterService"));

    private final Map<QueryId, DynamicFilterContext> dynamicFilterContexts = new ConcurrentHashMap<>();

    @Inject
    public DynamicFilterService(FeaturesConfig featuresConfig)
    {
        this.dynamicFilteringRefreshInterval = requireNonNull(featuresConfig, "featuresConfig is null").getDynamicFilteringRefreshInterval();
    }

    @PostConstruct
    public void start()
    {
        collectDynamicFiltersExecutor.scheduleWithFixedDelay(this::collectDynamicFilters, 0, dynamicFilteringRefreshInterval.toMillis(), MILLISECONDS);
    }

    @PreDestroy
    public void stop()
    {
        collectDynamicFiltersExecutor.shutdownNow();
    }

    public void registerQuery(SqlQueryExecution sqlQueryExecution, SubPlan fragmentedPlan)
    {
        PlanNode queryPlan = sqlQueryExecution.getQueryPlan().getRoot();
        Set<DynamicFilterId> dynamicFilters = getProducedDynamicFilters(queryPlan);
        Set<DynamicFilterId> replicatedDynamicFilters = getReplicatedDynamicFilters(queryPlan);

        Set<DynamicFilterId> lazyDynamicFilters = fragmentedPlan.getAllFragments().stream()
                .flatMap(plan -> getLazyDynamicFilters(plan).stream())
                .collect(toImmutableSet());

        // register query only if it contains dynamic filters
        if (!dynamicFilters.isEmpty()) {
            registerQuery(
                    sqlQueryExecution.getQueryId(),
                    sqlQueryExecution::getStageDynamicFilters,
                    dynamicFilters,
                    lazyDynamicFilters,
                    replicatedDynamicFilters);
        }
    }

    @VisibleForTesting
    void registerQuery(
            QueryId queryId,
            Supplier<List<StageDynamicFilters>> stageDynamicFiltersSupplier,
            Set<DynamicFilterId> dynamicFilters,
            Set<DynamicFilterId> lazyDynamicFilters,
            Set<DynamicFilterId> replicatedDynamicFilters)
    {
        Map<DynamicFilterId, SettableFuture<?>> lazyDynamicFilterFutures = lazyDynamicFilters.stream()
                .collect(toImmutableMap(filter -> filter, filter -> SettableFuture.create()));
        dynamicFilterContexts.putIfAbsent(queryId, new DynamicFilterContext(
                stageDynamicFiltersSupplier,
                dynamicFilters,
                lazyDynamicFilterFutures,
                replicatedDynamicFilters));
    }

    public DynamicFiltersStats getDynamicFilteringStats(QueryId queryId, Session session)
    {
        DynamicFilterContext context = dynamicFilterContexts.get(queryId);
        if (context == null) {
            // query has been removed or dynamic filtering is not enabled
            return DynamicFiltersStats.EMPTY;
        }

        int lazyFilters = context.getLazyDynamicFilters().size();
        int replicatedFilters = context.getReplicatedDynamicFilters().size();
        int totalDynamicFilters = context.getTotalDynamicFilters();

        List<DynamicFilterDomainStats> dynamicFilterDomainStats = context.getDynamicFilterSummaries().entrySet().stream()
                .map(entry -> {
                    DynamicFilterId dynamicFilterId = entry.getKey();
                    Domain domain = entry.getValue();
                    // simplify for readability
                    String simplifiedDomain = domain.simplify(1).toString(session.toConnectorSession());
                    int rangeCount = domain.getValues().getValuesProcessor().transform(
                            Ranges::getRangeCount,
                            discreteValues -> 0,
                            allOrNone -> 0);
                    int discreteValuesCount = domain.getValues().getValuesProcessor().transform(
                            ranges -> 0,
                            DiscreteValues::getValuesCount,
                            allOrNone -> 0);
                    return new DynamicFilterDomainStats(dynamicFilterId, simplifiedDomain, rangeCount, discreteValuesCount);
                })
                .collect(toImmutableList());
        return new DynamicFiltersStats(
                dynamicFilterDomainStats,
                lazyFilters,
                replicatedFilters,
                totalDynamicFilters,
                dynamicFilterDomainStats.size());
    }

    public void removeQuery(QueryId queryId)
    {
        dynamicFilterContexts.remove(queryId);
    }

    public DynamicFilter createDynamicFilter(QueryId queryId, List<DynamicFilters.Descriptor> dynamicFilterDescriptors, Map<Symbol, ColumnHandle> columnHandles)
    {
        Map<DynamicFilterId, ColumnHandle> sourceColumnHandles = extractSourceColumnHandles(dynamicFilterDescriptors, columnHandles);
        Set<DynamicFilterId> dynamicFilters = dynamicFilterDescriptors.stream()
                .map(DynamicFilters.Descriptor::getId)
                .collect(toImmutableSet());
        DynamicFilterContext context = dynamicFilterContexts.get(queryId);
        if (context == null) {
            // query has been removed
            return EMPTY;
        }

        List<ListenableFuture<?>> lazyDynamicFilterFutures = dynamicFilters.stream()
                .map(context.getLazyDynamicFilters()::get)
                .filter(Objects::nonNull)
                .collect(toImmutableList());
        AtomicReference<TupleDomain<ColumnHandle>> completedDynamicFilter = new AtomicReference<>();

        return new DynamicFilter()
        {
            @Override
            public CompletableFuture<?> isBlocked()
            {
                // wait for any of the requested dynamic filter domains to be completed
                List<ListenableFuture<?>> undoneFutures = lazyDynamicFilterFutures.stream()
                        .filter(future -> !future.isDone())
                        .collect(toImmutableList());

                if (undoneFutures.isEmpty()) {
                    return NOT_BLOCKED;
                }

                return toCompletableFuture(whenAnyComplete(undoneFutures));
            }

            @Override
            public boolean isComplete()
            {
                return dynamicFilters.stream()
                        .allMatch(context.getDynamicFilterSummaries()::containsKey);
            }

            @Override
            public TupleDomain<ColumnHandle> getCurrentPredicate()
            {
                TupleDomain<ColumnHandle> dynamicFilter = completedDynamicFilter.get();
                if (dynamicFilter != null) {
                    return dynamicFilter;
                }

                dynamicFilter = dynamicFilters.stream()
                        .map(filter -> Optional.ofNullable(context.getDynamicFilterSummaries().get(filter))
                                .map(summary -> translateSummaryToTupleDomain(filter, summary, sourceColumnHandles)))
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .reduce(TupleDomain.all(), TupleDomain::intersect);

                if (isComplete()) {
                    completedDynamicFilter.set(dynamicFilter);
                }

                return dynamicFilter;
            }
        };
    }

    @VisibleForTesting
    void collectDynamicFilters()
    {
        for (DynamicFilterContext context : dynamicFilterContexts.values()) {
            if (context.isCompleted()) {
                continue;
            }

            Set<DynamicFilterId> uncollectedFilters = context.getUncollectedDynamicFilters();
            ImmutableMap.Builder<DynamicFilterId, Domain> newDynamicFiltersBuilder = ImmutableMap.builder();
            for (StageDynamicFilters stageDynamicFilters : context.getDynamicFilterSupplier().get()) {
                StageState stageState = stageDynamicFilters.getStageState();
                stageDynamicFilters.getTaskDynamicFilters().stream()
                        .flatMap(taskDomains -> taskDomains.entrySet().stream())
                        .filter(domain -> uncollectedFilters.contains(domain.getKey()))
                        .collect(groupingBy(Map.Entry::getKey, mapping(Map.Entry::getValue, toImmutableList())))
                        .entrySet().stream()
                        .filter(stageDomains -> {
                            if (stageDomains.getValue().stream().anyMatch(Domain::isAll)) {
                                // if one of the domains is all, we don't need to get dynamic filters from all tasks
                                return true;
                            }

                            if (context.getReplicatedDynamicFilters().contains(stageDomains.getKey())) {
                                // for replicated dynamic filters it's enough to get dynamic filter from a single task
                                return true;
                            }

                            // check if all tasks of a dynamic filter source have reported dynamic filter summary
                            return !stageState.canScheduleMoreTasks() && stageDomains.getValue().size() == stageDynamicFilters.getNumberOfTasks();
                        })
                        .forEach(stageDomains -> newDynamicFiltersBuilder.put(stageDomains.getKey(), union(stageDomains.getValue())));
            }

            context.addDynamicFilters(newDynamicFiltersBuilder.build());
        }
    }

    @VisibleForTesting
    Optional<Domain> getSummary(QueryId queryId, DynamicFilterId filterId)
    {
        return Optional.ofNullable(dynamicFilterContexts.get(queryId).getDynamicFilterSummaries().get(filterId));
    }

    private static TupleDomain<ColumnHandle> translateSummaryToTupleDomain(DynamicFilterId filterId, Domain summary, Map<DynamicFilterId, ColumnHandle> sourceColumnHandles)
    {
        ColumnHandle sourceColumnHandle = requireNonNull(sourceColumnHandles.get(filterId), () -> format("Source column handle for dynamic filter %s is null", filterId));
        return TupleDomain.withColumnDomains(ImmutableMap.of(sourceColumnHandle, summary));
    }

    private static Map<DynamicFilterId, ColumnHandle> extractSourceColumnHandles(List<DynamicFilters.Descriptor> dynamicFilters, Map<Symbol, ColumnHandle> columnHandles)
    {
        return dynamicFilters.stream()
                .collect(toImmutableMap(
                        DynamicFilters.Descriptor::getId,
                        descriptor -> columnHandles.get(Symbol.from(descriptor.getInput()))));
    }

    private static Set<DynamicFilterId> getLazyDynamicFilters(PlanFragment plan)
    {
        // lazy dynamic filters cannot be consumed by the same stage where they are produced as it would result in query deadlock
        return difference(getProducedDynamicFilters(plan.getRoot()), getConsumedDynamicFilters(plan.getRoot()));
    }

    private static Set<DynamicFilterId> getReplicatedDynamicFilters(PlanNode planNode)
    {
        return PlanNodeSearcher.searchFrom(planNode)
                .where(JoinNode.class::isInstance)
                .<JoinNode>findAll().stream()
                .filter(JoinUtils::isBuildSideReplicated)
                .flatMap(node -> node.getDynamicFilters().keySet().stream())
                .collect(toImmutableSet());
    }

    private static Set<DynamicFilterId> getProducedDynamicFilters(PlanNode planNode)
    {
        return PlanNodeSearcher.searchFrom(planNode)
                .where(JoinNode.class::isInstance)
                .<JoinNode>findAll().stream()
                .flatMap(node -> node.getDynamicFilters().keySet().stream())
                .collect(toImmutableSet());
    }

    private static Set<DynamicFilterId> getConsumedDynamicFilters(PlanNode planNode)
    {
        return extractExpressions(planNode).stream()
                .flatMap(expression -> extractDynamicFilters(expression).getDynamicConjuncts().stream()
                        .map(DynamicFilters.Descriptor::getId))
                .collect(toImmutableSet());
    }

    public static class StageDynamicFilters
    {
        private final StageState stageState;
        private final int numberOfTasks;
        private final List<Map<DynamicFilterId, Domain>> taskDynamicFilters;

        public StageDynamicFilters(StageState stageState, int numberOfTasks, List<Map<DynamicFilterId, Domain>> taskDynamicFilters)
        {
            this.stageState = requireNonNull(stageState, "stageState is null");
            this.numberOfTasks = numberOfTasks;
            this.taskDynamicFilters = ImmutableList.copyOf(requireNonNull(taskDynamicFilters, "taskDynamicFilters is null"));
        }

        private StageState getStageState()
        {
            return stageState;
        }

        private int getNumberOfTasks()
        {
            return numberOfTasks;
        }

        private List<Map<DynamicFilterId, Domain>> getTaskDynamicFilters()
        {
            return taskDynamicFilters;
        }
    }

    public static class DynamicFiltersStats
    {
        public static final DynamicFiltersStats EMPTY = new DynamicFiltersStats(ImmutableList.of(), 0, 0, 0, 0);

        private final List<DynamicFilterDomainStats> dynamicFilterDomainStats;
        private final int lazyDynamicFilters;
        private final int replicatedDynamicFilters;
        private final int totalDynamicFilters;
        private final int dynamicFiltersCompleted;

        @JsonCreator
        public DynamicFiltersStats(
                @JsonProperty("dynamicFilterDomainStats") List<DynamicFilterDomainStats> dynamicFilterDomainStats,
                @JsonProperty("lazyDynamicFilters") int lazyDynamicFilters,
                @JsonProperty("replicatedDynamicFilters") int replicatedDynamicFilters,
                @JsonProperty("totalDynamicFilters") int totalDynamicFilters,
                @JsonProperty("dynamicFiltersCompleted") int dynamicFiltersCompleted)
        {
            this.dynamicFilterDomainStats = dynamicFilterDomainStats;
            this.lazyDynamicFilters = lazyDynamicFilters;
            this.replicatedDynamicFilters = replicatedDynamicFilters;
            this.totalDynamicFilters = totalDynamicFilters;
            this.dynamicFiltersCompleted = dynamicFiltersCompleted;
        }

        @JsonProperty
        public List<DynamicFilterDomainStats> getDynamicFilterDomainStats()
        {
            return dynamicFilterDomainStats;
        }

        @JsonProperty
        public int getLazyDynamicFilters()
        {
            return lazyDynamicFilters;
        }

        @JsonProperty
        public int getReplicatedDynamicFilters()
        {
            return replicatedDynamicFilters;
        }

        @JsonProperty
        public int getTotalDynamicFilters()
        {
            return totalDynamicFilters;
        }

        @JsonProperty
        public int getDynamicFiltersCompleted()
        {
            return dynamicFiltersCompleted;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            DynamicFiltersStats that = (DynamicFiltersStats) o;
            return lazyDynamicFilters == that.lazyDynamicFilters &&
                    replicatedDynamicFilters == that.replicatedDynamicFilters &&
                    totalDynamicFilters == that.totalDynamicFilters &&
                    dynamicFiltersCompleted == that.dynamicFiltersCompleted &&
                    Objects.equals(dynamicFilterDomainStats, that.dynamicFilterDomainStats);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(dynamicFilterDomainStats, lazyDynamicFilters, replicatedDynamicFilters, totalDynamicFilters, dynamicFiltersCompleted);
        }
    }

    public static class DynamicFilterDomainStats
    {
        private final DynamicFilterId dynamicFilterId;
        private final String simplifiedDomain;
        private final int rangeCount;
        private final int discreteValuesCount;

        @JsonCreator
        public DynamicFilterDomainStats(
                @JsonProperty("dynamicFilterId") DynamicFilterId dynamicFilterId,
                @JsonProperty("simplifiedDomain") String simplifiedDomain,
                @JsonProperty("rangeCount") int rangeCount,
                @JsonProperty("discreteValuesCount") int discreteValuesCount)
        {
            this.dynamicFilterId = dynamicFilterId;
            this.simplifiedDomain = simplifiedDomain;
            this.rangeCount = rangeCount;
            this.discreteValuesCount = discreteValuesCount;
        }

        @JsonProperty
        public DynamicFilterId getDynamicFilterId()
        {
            return dynamicFilterId;
        }

        @JsonProperty
        public String getSimplifiedDomain()
        {
            return simplifiedDomain;
        }

        @JsonProperty
        public int getRangeCount()
        {
            return rangeCount;
        }

        @JsonProperty
        public int getDiscreteValuesCount()
        {
            return discreteValuesCount;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            DynamicFilterDomainStats that = (DynamicFilterDomainStats) o;
            return rangeCount == that.rangeCount &&
                    discreteValuesCount == that.discreteValuesCount &&
                    Objects.equals(dynamicFilterId, that.dynamicFilterId) &&
                    Objects.equals(simplifiedDomain, that.simplifiedDomain);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(dynamicFilterId, simplifiedDomain, rangeCount, discreteValuesCount);
        }
    }

    private static class DynamicFilterContext
    {
        final Map<DynamicFilterId, Domain> dynamicFilterSummaries = new ConcurrentHashMap<>();
        final Supplier<List<StageDynamicFilters>> dynamicFilterSupplier;
        final Set<DynamicFilterId> dynamicFilters;
        final Map<DynamicFilterId, SettableFuture<?>> lazyDynamicFilters;
        final Set<DynamicFilterId> replicatedDynamicFilters;
        final AtomicBoolean completed = new AtomicBoolean();

        public DynamicFilterContext(
                Supplier<List<StageDynamicFilters>> dynamicFilterSupplier,
                Set<DynamicFilterId> dynamicFilters,
                Map<DynamicFilterId, SettableFuture<?>> lazyDynamicFilters,
                Set<DynamicFilterId> replicatedDynamicFilters)
        {
            this.dynamicFilterSupplier = requireNonNull(dynamicFilterSupplier, "dynamicFilterSupplier is null");
            this.dynamicFilters = requireNonNull(dynamicFilters, "dynamicFilters is null");
            this.lazyDynamicFilters = requireNonNull(lazyDynamicFilters, "lazyDynamicFilters is null");
            this.replicatedDynamicFilters = requireNonNull(replicatedDynamicFilters, "replicatedDynamicFilters is null");
        }

        private int getTotalDynamicFilters()
        {
            return dynamicFilters.size();
        }

        private Set<DynamicFilterId> getUncollectedDynamicFilters()
        {
            return dynamicFilters.stream()
                    .filter(filter -> !dynamicFilterSummaries.containsKey(filter))
                    .collect(toImmutableSet());
        }

        private void addDynamicFilters(Map<DynamicFilterId, Domain> newDynamicFilters)
        {
            newDynamicFilters.forEach((filter, domain) -> {
                dynamicFilterSummaries.put(filter, domain);
                SettableFuture<?> future = lazyDynamicFilters.get(filter);
                if (future != null) {
                    checkState(future.set(null), "Same future set twice");
                }
            });

            // stop collecting dynamic filters for query when all dynamic filters have been collected
            completed.set(dynamicFilters.stream().allMatch(dynamicFilterSummaries::containsKey));
        }

        private Map<DynamicFilterId, Domain> getDynamicFilterSummaries()
        {
            return dynamicFilterSummaries;
        }

        public Supplier<List<StageDynamicFilters>> getDynamicFilterSupplier()
        {
            return dynamicFilterSupplier;
        }

        public Map<DynamicFilterId, SettableFuture<?>> getLazyDynamicFilters()
        {
            return lazyDynamicFilters;
        }

        public Set<DynamicFilterId> getReplicatedDynamicFilters()
        {
            return replicatedDynamicFilters;
        }

        public boolean isCompleted()
        {
            return completed.get();
        }
    }
}
