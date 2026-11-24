/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.routing.allocation.allocator;

import org.opensearch.Version;
import org.opensearch.cluster.ClusterInfo;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.OpenSearchAllocationTestCase;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.RoutingNode;
import org.opensearch.cluster.routing.RoutingNodes;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.routing.ShardRoutingState;
import org.opensearch.cluster.routing.TestShardRouting;
import org.opensearch.cluster.routing.allocation.AllocationService;
import org.opensearch.cluster.routing.allocation.RoutingAllocation;
import org.opensearch.cluster.routing.allocation.allocator.BalancedShardsAllocator.ModelNode;
import org.opensearch.cluster.routing.allocation.allocator.BalancedShardsAllocator.NodeSorter;
import org.opensearch.cluster.routing.allocation.allocator.BalancedShardsAllocator.WeightFunction;
import org.opensearch.cluster.routing.allocation.decider.AllocationDecider;
import org.opensearch.cluster.routing.allocation.decider.AllocationDeciders;
import org.opensearch.cluster.routing.allocation.decider.Decision;
import org.opensearch.cluster.routing.allocation.decider.SameShardAllocationDecider;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class LocalShardsBalancerTests extends OpenSearchAllocationTestCase {

    private final DiscoveryNode node1 = newNode("node1", "node1", Collections.singletonMap("zone", "1a"));
    private final DiscoveryNode node2 = newNode("node2", "node2", Collections.singletonMap("zone", "1b"));
    private final DiscoveryNode node3 = newNode("node3", "node3", Collections.singletonMap("zone", "1c"));
    private final DiscoveryNode node4 = newNode("node4", "node4", Collections.singletonMap("zone", "1a"));
    private final DiscoveryNode node5 = newNode("node5", "node5", Collections.singletonMap("zone", "1b"));
    private final DiscoveryNode node6 = newNode("node6", "node6", Collections.singletonMap("zone", "1c"));

    public void testAllocateUnassignedWhenAllShardsCanBeAllocated() {
        int numberOfIndices = 2;
        int numberOfShards = 1;
        int numberOfReplicas = 2;
        int numberOfSearchReplicas = 3;

        Metadata metadata = buildMetadata(Metadata.builder(), numberOfIndices, numberOfShards, numberOfReplicas, numberOfSearchReplicas);
        RoutingTable routingTable = buildRoutingTable(metadata);
        ClusterState state = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(routingTable)
            .nodes(DiscoveryNodes.builder().add(node1).add(node2).add(node3).add(node4).add(node5).add(node6))
            .build();

        RoutingAllocation allocation = new RoutingAllocation(
            yesAllocationDeciders(),
            new RoutingNodes(state, false),
            state,
            ClusterInfo.EMPTY,
            null,
            System.nanoTime()
        );

        BalancedShardsAllocator.WeightFunction weightFunction = mock(BalancedShardsAllocator.WeightFunction.class);
        when(weightFunction.weightWithAllocationConstraints(any(), any(), any())).thenReturn(0.5F);

        final ShardsBalancer localShardsBalancer = new LocalShardsBalancer(
            logger,
            allocation,
            null,
            weightFunction,
            0,
            false,
            false,
            false,
            false,
            null
        );

        localShardsBalancer.allocateUnassigned();

        List<ShardRouting> initializingShards = allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING);
        assertEquals(12, initializingShards.size());

        List<ShardRouting> unassignedShards = allocation.routingNodes().shardsWithState(ShardRoutingState.UNASSIGNED);
        assertEquals(0, unassignedShards.size());
    }

    public void testAllocateUnassignedWhenSearchShardsCannotBeAllocated() {
        int numberOfIndices = 2;
        int numberOfShards = 1;
        int numberOfReplicas = 2;
        int numberOfSearchReplicas = 3;

        Metadata metadata = buildMetadata(Metadata.builder(), numberOfIndices, numberOfShards, numberOfReplicas, numberOfSearchReplicas);
        RoutingTable routingTable = buildRoutingTable(metadata);
        ClusterState state = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(routingTable)
            .nodes(DiscoveryNodes.builder().add(node1).add(node2).add(node3).add(node4).add(node5).add(node6))
            .build();

        RoutingAllocation allocation = new RoutingAllocation(
            provideAllocationDecidersWithNoDecisionForSearchReplica(),
            new RoutingNodes(state, false),
            state,
            ClusterInfo.EMPTY,
            null,
            System.nanoTime()
        );

        BalancedShardsAllocator.WeightFunction weightFunction = mock(BalancedShardsAllocator.WeightFunction.class);
        when(weightFunction.weightWithAllocationConstraints(any(), any(), any())).thenReturn(0.5F);

        final ShardsBalancer localShardsBalancer = new LocalShardsBalancer(
            logger,
            allocation,
            null,
            weightFunction,
            0,
            false,
            false,
            false,
            false,
            null
        );

        localShardsBalancer.allocateUnassigned();

        List<ShardRouting> initializingShards = allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING);
        assertEquals(6, initializingShards.size());

        List<ShardRouting> unassignedShards = allocation.routingNodes().unassigned().ignored();
        assertEquals(6, unassignedShards.size());
    }

    public void testAllocateUnassignedWhenRegularReplicaShardsCannotBeAllocated() {
        int numberOfIndices = 2;
        int numberOfShards = 1;
        int numberOfReplicas = 2;
        int numberOfSearchReplicas = 3;

        Metadata metadata = buildMetadata(Metadata.builder(), numberOfIndices, numberOfShards, numberOfReplicas, numberOfSearchReplicas);
        RoutingTable routingTable = buildRoutingTable(metadata);
        ClusterState state = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(routingTable)
            .nodes(DiscoveryNodes.builder().add(node1).add(node2).add(node3).add(node4).add(node5).add(node6))
            .build();

        RoutingAllocation allocation = new RoutingAllocation(
            provideAllocationDecidersWithNoDecisionForRegularReplica(),
            new RoutingNodes(state, false),
            state,
            ClusterInfo.EMPTY,
            null,
            System.nanoTime()
        );

        BalancedShardsAllocator.WeightFunction weightFunction = mock(BalancedShardsAllocator.WeightFunction.class);
        when(weightFunction.weightWithAllocationConstraints(any(), any(), any())).thenReturn(0.5F);

        final ShardsBalancer localShardsBalancer = new LocalShardsBalancer(
            logger,
            allocation,
            null,
            weightFunction,
            0,
            false,
            false,
            false,
            false,
            null
        );

        localShardsBalancer.allocateUnassigned();

        List<ShardRouting> initializingShards = allocation.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING);
        assertEquals(8, initializingShards.size());

        List<ShardRouting> unassignedShards = allocation.routingNodes().unassigned().ignored();
        assertEquals(4, unassignedShards.size());
    }

    private RoutingTable buildRoutingTable(Metadata metadata) {
        RoutingTable.Builder routingTableBuilder = RoutingTable.builder();
        for (Map.Entry<String, IndexMetadata> entry : metadata.getIndices().entrySet()) {
            routingTableBuilder.addAsNew(entry.getValue());
        }
        return routingTableBuilder.build();
    }

    private Metadata buildMetadata(
        Metadata.Builder mb,
        int numberOfIndices,
        int numberOfShards,
        int numberOfReplicas,
        int numberOfSearchReplicas
    ) {
        for (int i = 0; i < numberOfIndices; i++) {
            mb.put(
                IndexMetadata.builder("test_" + i)
                    .settings(settings(Version.CURRENT))
                    .numberOfShards(numberOfShards)
                    .numberOfReplicas(numberOfReplicas)
                    .numberOfSearchReplicas(numberOfSearchReplicas)
            );
        }

        return mb.build();
    }

    private AllocationDeciders provideAllocationDecidersWithNoDecisionForSearchReplica() {
        return new AllocationDeciders(Arrays.asList(new TestAllocateDecision((shardRouting -> {
            if (shardRouting.isSearchOnly()) {
                return Decision.NO;
            } else {
                return Decision.YES;
            }
        })), new SameShardAllocationDecider(Settings.EMPTY, new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS)))
        );
    }

    private AllocationDeciders provideAllocationDecidersWithNoDecisionForRegularReplica() {
        return new AllocationDeciders(Arrays.asList(new TestAllocateDecision((shardRouting -> {
            if (!shardRouting.isSearchOnly() && !shardRouting.primary()) {
                return Decision.NO;
            } else {
                return Decision.YES;
            }
        })), new SameShardAllocationDecider(Settings.EMPTY, new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS)))
        );
    }

    public static class TestAllocateDecision extends AllocationDecider {

        private final Function<ShardRouting, Decision> decider;

        public TestAllocateDecision(Function<ShardRouting, Decision> decider) {
            this.decider = decider;
        }

        @Override
        public Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
            return decider.apply(shardRouting);
        }

        @Override
        public Decision canAllocate(ShardRouting shardRouting, RoutingAllocation allocation) {
            return decider.apply(shardRouting);
        }
    }

    public void testAvgShardsPerNodeRefreshesWhenFilterChanges() {
        int numberOfShards = 6;
        Metadata metadata = buildMetadata(Metadata.builder(), 1, numberOfShards, 1, 0);
        RoutingTable routingTable = buildRoutingTable(metadata);
        ClusterState state = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(routingTable)
            .nodes(DiscoveryNodes.builder().add(node1).add(node2).add(node3).add(node4).add(node5).add(node6))
            .build();

        // Drive all shards to STARTED so totalShardCount reflects the assigned shard count (12).
        AllocationService allocationService = createAllocationService(Settings.EMPTY);
        state = allocationService.reroute(state, "initial-allocation");
        state = applyStartedShardsUntilNoChange(state, allocationService);
        assertEquals(12, state.getRoutingNodes().shardsWithState(ShardRoutingState.STARTED).size());

        // ---- Round 1: exclude {node4,node5,node6} -> 3 eligible -> 6 / 3 = 2.0, total 12 / 3 = 4.0
        RoutingAllocation allocation1 = new RoutingAllocation(
            new AllocationDeciders(
                Collections.singletonList(new StaticClusterFilterDecider(new HashSet<>(Arrays.asList("node4", "node5", "node6"))))
            ),
            new RoutingNodes(state, false),
            state,
            ClusterInfo.EMPTY,
            null,
            System.nanoTime()
        );
        LocalShardsBalancer balancer1 = new LocalShardsBalancer(
            logger,
            allocation1,
            null,
            mock(BalancedShardsAllocator.WeightFunction.class),
            0,
            false,
            false,
            true,
            false,
            null
        );
        assertEquals("round1 primary per-index", 2.0f, balancer1.avgPrimaryShardsPerNode("test_0"), 0.0001f);
        assertEquals("round1 primary cluster-level", 2.0f, balancer1.avgPrimaryShardsPerNode(), 0.0001f);
        assertEquals("round1 shards per-index", 12.0f / 3.0f, balancer1.avgShardsPerNode("test_0"), 0.0001f);
        assertEquals("round1 shards cluster-level", 12.0f / 3.0f, balancer1.avgShardsPerNode(), 0.0001f);

        // ---- Round 2: filter shrinks to {node6} -> 5 eligible -> 6 / 5 = 1.2
        RoutingAllocation allocation2 = new RoutingAllocation(
            new AllocationDeciders(
                Collections.singletonList(new StaticClusterFilterDecider(new HashSet<>(Collections.singletonList("node6"))))
            ),
            new RoutingNodes(state, false),
            state,
            ClusterInfo.EMPTY,
            null,
            System.nanoTime()
        );
        LocalShardsBalancer balancer2 = new LocalShardsBalancer(
            logger,
            allocation2,
            null,
            mock(BalancedShardsAllocator.WeightFunction.class),
            0,
            false,
            false,
            true,
            false,
            null
        );
        assertEquals("round2 primary per-index", 6.0f / 5.0f, balancer2.avgPrimaryShardsPerNode("test_0"), 0.0001f);
        assertEquals("round2 primary cluster-level", 6.0f / 5.0f, balancer2.avgPrimaryShardsPerNode(), 0.0001f);
        assertEquals("round2 shards per-index", 12.0f / 5.0f, balancer2.avgShardsPerNode("test_0"), 0.0001f);
        assertEquals("round2 shards cluster-level", 12.0f / 5.0f, balancer2.avgShardsPerNode(), 0.0001f);

        // ---- Round 3: filter cleared -> 6 eligible -> 6 / 6 = 1.0
        RoutingAllocation allocation3 = new RoutingAllocation(
            new AllocationDeciders(Collections.singletonList(new StaticClusterFilterDecider(Collections.emptySet()))),
            new RoutingNodes(state, false),
            state,
            ClusterInfo.EMPTY,
            null,
            System.nanoTime()
        );
        LocalShardsBalancer balancer3 = new LocalShardsBalancer(
            logger,
            allocation3,
            null,
            mock(BalancedShardsAllocator.WeightFunction.class),
            0,
            false,
            false,
            true,
            false,
            null
        );
        assertEquals("round3 primary per-index", 1.0f, balancer3.avgPrimaryShardsPerNode("test_0"), 0.0001f);
        assertEquals("round3 primary cluster-level", 1.0f, balancer3.avgPrimaryShardsPerNode(), 0.0001f);
        assertEquals("round3 shards per-index", 2.0f, balancer3.avgShardsPerNode("test_0"), 0.0001f);
        assertEquals("round3 shards cluster-level", 2.0f, balancer3.avgShardsPerNode(), 0.0001f);
    }

    public static class StaticClusterFilterDecider extends AllocationDecider {
        private final Set<String> excluded;

        public StaticClusterFilterDecider(Set<String> excluded) {
            this.excluded = excluded;
        }

        @Override
        public Decision canAllocateAnyShardToNode(RoutingNode node, RoutingAllocation allocation) {
            return excluded.contains(node.nodeId()) ? Decision.NO : Decision.ALWAYS;
        }
    }

    public void testNodeSorterPreparesIndexAverageOncePerReset() {
        Metadata metadata = nodeSorterMetadata();
        WeightFunction function = new WeightFunction(0.55f, 0.45f, 0.1f, 37, false, false);
        LocalShardsBalancer balancer = nodeSorterBalancer(metadata, function);
        NodeSorter sorter = new NodeSorter(nodeSorterModels(), function, balancer);
        clearInvocations(metadata);

        sorter.reset("test_0");
        verify(metadata, times(1)).index("test_0");

        sorter.reset("test_0", 1, 3);
        verify(metadata, times(2)).index("test_0");

        sorter.reset("test_1");
        verify(metadata, times(1)).index("test_1");
    }

    public void testNodeSorterPreservesWeightsAndPartialSort() {
        assertNodeSorterPreservesWeights(false);
    }

    public void testNodeSorterPreservesPrimaryConstraints() {
        assertNodeSorterPreservesWeights(true);
    }

    private void assertNodeSorterPreservesWeights(boolean primaryConstraints) {
        Metadata metadata = nodeSorterMetadata();
        WeightFunction function = new WeightFunction(0.55f, 0.45f, 0.1f, 37, primaryConstraints, primaryConstraints);
        WeightFunction referenceFunction = new WeightFunction(0.55f, 0.45f, 0.1f, 37, primaryConstraints, primaryConstraints) {
        };
        LocalShardsBalancer balancer = nodeSorterBalancer(metadata, function);
        ModelNode[] models = nodeSorterModels();
        NodeSorter sorter = new NodeSorter(models.clone(), function, balancer);
        NodeSorter reference = new NodeSorter(models.clone(), referenceFunction, balancer);
        assertEquals(1.5f, balancer.avgShardsPerNode("test_0"), 0.0f);

        for (String index : List.of("test_0", "test_1")) {
            ModelNode untouchedNode = sorter.modelNodes[0];
            float untouchedWeight = sorter.weights[0];
            sorter.reset(index, 1, 3);
            reference.reset(index, 1, 3);
            assertSame(untouchedNode, sorter.modelNodes[0]);
            assertEquals(untouchedWeight, sorter.weights[0], 0.0f);
            assertArrayEquals(reference.modelNodes, sorter.modelNodes);
            assertArrayEquals(reference.weights, sorter.weights, 0.0f);

            sorter.reset(index);
            reference.reset(index);
            assertArrayEquals(reference.modelNodes, sorter.modelNodes);
            assertArrayEquals(reference.weights, sorter.weights, 0.0f);
        }
    }

    public void testNodeSorterKeepsRelocationWeightsAndPrimaryThresholdLive() {
        WeightFunction function = new WeightFunction(0.55f, 0.45f, 0.1f, 37, false, true);
        LocalShardsBalancer balancer = nodeSorterBalancer(nodeSorterMetadata(), function);
        ModelNode[] models = nodeSorterModels();
        NodeSorter sorter = new NodeSorter(models.clone(), function, balancer);
        sorter.reset("test_0");
        ModelNode source = models[0];
        ModelNode target = models[2];
        float sourceWeight = sorter.weight(source);
        float targetWeight = sorter.weight(target);
        ShardRouting shard = source.getIndex("test_0").iterator().next();

        source.removeShard(shard);
        target.addShard(shard);
        assertEquals(sourceWeight - 1.0f - 37.0f, sorter.weight(source), 0.0001f);
        assertEquals(targetWeight + 1.0f + 37.0f, sorter.weight(target), 0.0001f);
        int sourcePosition = Arrays.asList(sorter.modelNodes).indexOf(source);
        int targetPosition = Arrays.asList(sorter.modelNodes).indexOf(target);
        sorter.weights[sourcePosition] = sorter.weight(source);
        sorter.weights[targetPosition] = sorter.weight(target);
        sorter.sort(0, 3);
        for (int i = 0; i < sorter.modelNodes.length; i++) {
            assertEquals(function.weightWithRebalanceConstraints(balancer, sorter.modelNodes[i], "test_0"), sorter.weights[i], 0.0f);
            if (i > 0) {
                assertTrue(sorter.weights[i - 1] <= sorter.weights[i]);
            }
        }

        function.updatePrimaryConstraintThreshold(73);
        assertEquals(targetWeight + 1.0f + 73.0f, sorter.weight(target), 0.0001f);
        sorter.reset("test_0");
        for (int i = 0; i < sorter.modelNodes.length; i++) {
            assertEquals(function.weightWithRebalanceConstraints(balancer, sorter.modelNodes[i], "test_0"), sorter.weights[i], 0.0f);
        }
    }

    public void testNodeSorterRefreshesAveragesAfterAllocation() {
        WeightFunction function = new WeightFunction(0.55f, 0.45f, 0.1f, 37, false, false);
        LocalShardsBalancer balancer = nodeSorterBalancer(nodeSorterMetadata(), function);
        ModelNode[] models = nodeSorterModels();
        NodeSorter sorter = new NodeSorter(models, function, balancer);
        sorter.reset("test_0");
        ModelNode node = models[0];
        float previousWeight = sorter.weight(node);
        assertEquals(0.0f, balancer.avgShardsPerNode(), 0.0f);

        balancer.allocateUnassigned();

        assertEquals(3.5f, balancer.avgShardsPerNode(), 0.0f);
        assertEquals(previousWeight - 0.45f * 3.5f, sorter.weight(node), 0.0001f);
        sorter.reset("test_0");
        for (int i = 0; i < models.length; i++) {
            assertEquals(function.weightWithRebalanceConstraints(balancer, models[i], "test_0"), sorter.weights[i], 0.0f);
        }
    }

    public void testNodeSorterEmptyRangeDoesNotReadMetadata() {
        Metadata metadata = nodeSorterMetadata();
        WeightFunction function = new WeightFunction(0.55f, 0.45f, 0.1f, 37, false, false);
        LocalShardsBalancer balancer = nodeSorterBalancer(metadata, function);
        NodeSorter sorter = new NodeSorter(nodeSorterModels(), function, balancer);
        clearInvocations(metadata);

        sorter.reset("missing", 1, 1);
        new NodeSorter(new ModelNode[0], function, balancer).reset("missing");

        verifyNoInteractions(metadata);
    }

    public void testNodeSorterPreservesDerivedBalancerDispatch() {
        WeightFunction function = new WeightFunction(0.55f, 0.45f, 0.1f, 37, false, false);
        LocalShardsBalancer derivedBalancer = spy(nodeSorterBalancer(nodeSorterMetadata(), function));
        when(derivedBalancer.avgShardsPerNode()).thenReturn(0.25f, 0.5f, 0.75f);
        when(derivedBalancer.avgShardsPerNode("test_0")).thenReturn(1.25f, 1.5f, 1.75f);
        ModelNode[] models = nodeSorterModels();
        NodeSorter sorter = new NodeSorter(models.clone(), function, derivedBalancer);
        clearInvocations(derivedBalancer);

        sorter.reset("test_0");

        verify(derivedBalancer, times(3)).avgShardsPerNode();
        verify(derivedBalancer, times(3)).avgShardsPerNode("test_0");
        for (int i = 0; i < models.length; i++) {
            float average = 0.25f * (i + 1);
            float expected = 0.45f * (models[i].numShards() - average) + 0.55f * (models[i].numShards("test_0") - 1.0f - average);
            int position = Arrays.asList(sorter.modelNodes).indexOf(models[i]);
            assertEquals(expected, sorter.weights[position], 0.0001f);
        }
    }

    public void testNodeSorterPreservesCustomWeightDispatch() {
        WeightFunction function = new WeightFunction(0.55f, 0.45f, 0.1f, 37, false, false) {
            @Override
            float weight(ShardsBalancer balancer, ModelNode node, String index) {
                return -node.numShards(index);
            }
        };
        assertCustomNodeSorterDispatch(function);
    }

    public void testNodeSorterPreservesCustomRebalanceWeightDispatch() {
        WeightFunction function = new WeightFunction(0.55f, 0.45f, 0.1f, 37, false, false) {
            @Override
            public float weightWithRebalanceConstraints(ShardsBalancer balancer, ModelNode node, String index) {
                return -node.numShards(index);
            }
        };
        assertCustomNodeSorterDispatch(function);
    }

    private void assertCustomNodeSorterDispatch(WeightFunction function) {
        Metadata metadata = nodeSorterMetadata();
        LocalShardsBalancer balancer = nodeSorterBalancer(metadata, function);
        NodeSorter sorter = new NodeSorter(nodeSorterModels(), function, balancer);
        clearInvocations(metadata);

        sorter.reset("test_0");

        verifyNoInteractions(metadata);
        for (int i = 0; i < sorter.modelNodes.length; i++) {
            assertEquals(-sorter.modelNodes[i].numShards("test_0"), sorter.weights[i], 0.0f);
        }
        assertSame(node1, sorter.modelNodes[0].getRoutingNode().node());
    }

    private Metadata nodeSorterMetadata() {
        return spy(
            Metadata.builder()
                .put(IndexMetadata.builder("test_0").settings(settings(Version.CURRENT)).numberOfShards(3).numberOfReplicas(0))
                .put(IndexMetadata.builder("test_1").settings(settings(Version.CURRENT)).numberOfShards(2).numberOfReplicas(1))
                .build()
        );
    }

    private LocalShardsBalancer nodeSorterBalancer(Metadata metadata, WeightFunction function) {
        ClusterState state = ClusterState.builder(ClusterName.DEFAULT)
            .metadata(metadata)
            .routingTable(buildRoutingTable(metadata))
            .nodes(DiscoveryNodes.builder().add(node1).add(node2).add(node3))
            .build();
        RoutingAllocation allocation = new RoutingAllocation(
            new AllocationDeciders(List.of(new StaticClusterFilterDecider(Set.of("node3")))),
            new RoutingNodes(state, false),
            state,
            ClusterInfo.EMPTY,
            null,
            System.nanoTime()
        );
        return new LocalShardsBalancer(logger, allocation, null, function, 0, false, false, true, false, null);
    }

    private ModelNode[] nodeSorterModels() {
        ModelNode first = new ModelNode(new RoutingNode(node1.getId(), node1));
        ModelNode second = new ModelNode(new RoutingNode(node2.getId(), node2));
        ModelNode third = new ModelNode(new RoutingNode(node3.getId(), node3));
        first.addShard(TestShardRouting.newShardRouting("test_0", 0, node1.getId(), true, ShardRoutingState.STARTED));
        first.addShard(TestShardRouting.newShardRouting("test_0", 1, node1.getId(), true, ShardRoutingState.STARTED));
        first.addShard(TestShardRouting.newShardRouting("test_0", 2, node1.getId(), true, ShardRoutingState.STARTED));
        third.addShard(TestShardRouting.newShardRouting("test_1", 0, node3.getId(), true, ShardRoutingState.STARTED));
        third.addShard(TestShardRouting.newShardRouting("test_1", 1, node3.getId(), true, ShardRoutingState.STARTED));
        return new ModelNode[] { first, second, third };
    }

}
