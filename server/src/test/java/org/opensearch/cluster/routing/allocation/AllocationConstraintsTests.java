/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.routing.allocation;

import org.opensearch.cluster.OpenSearchAllocationTestCase;
import org.opensearch.cluster.routing.allocation.allocator.BalancedShardsAllocator;
import org.opensearch.cluster.routing.allocation.allocator.LocalShardsBalancer;
import org.opensearch.cluster.routing.allocation.allocator.ShardsBalancer;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.opensearch.cluster.routing.allocation.ConstraintTypes.CLUSTER_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID;
import static org.opensearch.cluster.routing.allocation.ConstraintTypes.CLUSTER_PRIMARY_SHARD_REBALANCE_CONSTRAINT_ID;
import static org.opensearch.cluster.routing.allocation.ConstraintTypes.CONSTRAINT_WEIGHT;
import static org.opensearch.cluster.routing.allocation.ConstraintTypes.INDEX_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID;
import static org.opensearch.cluster.routing.allocation.ConstraintTypes.INDEX_SHARD_PER_NODE_BREACH_CONSTRAINT_ID;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AllocationConstraintsTests extends OpenSearchAllocationTestCase {

    long constraintWeight = 20L;

    public void testSettings() {
        Settings.Builder settings = Settings.builder();
        ClusterSettings service = new ClusterSettings(Settings.builder().build(), ClusterSettings.BUILT_IN_CLUSTER_SETTINGS);
        BalancedShardsAllocator allocator = new BalancedShardsAllocator(settings.build(), service);

        settings = Settings.builder();
        float indexBalanceFactor = randomFloat();
        float shardBalance = randomFloat();
        float threshold = randomFloat();
        settings.put(BalancedShardsAllocator.INDEX_BALANCE_FACTOR_SETTING.getKey(), indexBalanceFactor);
        settings.put(BalancedShardsAllocator.SHARD_BALANCE_FACTOR_SETTING.getKey(), shardBalance);
        settings.put(BalancedShardsAllocator.THRESHOLD_SETTING.getKey(), threshold);
        settings.put(BalancedShardsAllocator.PREFER_PRIMARY_SHARD_BALANCE.getKey(), true);

        service.applySettings(settings.build());

        assertEquals(indexBalanceFactor, allocator.getIndexBalance(), 0.01);
        assertEquals(shardBalance, allocator.getShardBalance(), 0.01);
        assertEquals(threshold, allocator.getThreshold(), 0.01);
        assertEquals(true, allocator.getPreferPrimaryBalance());

        settings.put(BalancedShardsAllocator.PREFER_PRIMARY_SHARD_BALANCE.getKey(), false);
        service.applySettings(settings.build());
        assertEquals(false, allocator.getPreferPrimaryBalance());
    }

    /**
     * Test constraint evaluation logic when with different values of ConstraintMode
     * for IndexShardPerNode constraint satisfied and breached.
     */
    public void testIndexShardsPerNodeConstraint() {
        ShardsBalancer balancer = mock(LocalShardsBalancer.class);
        BalancedShardsAllocator.ModelNode node = mock(BalancedShardsAllocator.ModelNode.class);
        AllocationConstraints constraints = new AllocationConstraints();
        constraints.updateAllocationConstraint(INDEX_SHARD_PER_NODE_BREACH_CONSTRAINT_ID, true);

        int shardCount = randomIntBetween(1, 500);
        float avgShardsPerNode = 1.0f + (random().nextFloat()) * 999.0f;

        when(balancer.avgShardsPerNode(anyString())).thenReturn(avgShardsPerNode);
        when(node.numShards(anyString())).thenReturn(shardCount);
        when(node.getNodeId()).thenReturn("test-node");

        long expectedWeight = (shardCount >= avgShardsPerNode) ? CONSTRAINT_WEIGHT : 0;
        assertEquals(expectedWeight, constraints.weight(balancer, node, "index", constraintWeight));

    }

    /**
     * Test constraint evaluation logic when with different values of ConstraintMode
     * for IndexShardPerNode constraint satisfied and breached.
     */
    public void testPerIndexPrimaryShardsConstraint() {
        ShardsBalancer balancer = mock(LocalShardsBalancer.class);
        BalancedShardsAllocator.ModelNode node = mock(BalancedShardsAllocator.ModelNode.class);
        AllocationConstraints constraints = new AllocationConstraints();
        constraints.updateAllocationConstraint(INDEX_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID, true);

        final String indexName = "test-index";
        int perIndexPrimaryShardCount = 1;
        float avgPerIndexPrimaryShardsPerNode = 2f;

        when(balancer.avgPrimaryShardsPerNode(anyString())).thenReturn(avgPerIndexPrimaryShardsPerNode);
        when(node.numPrimaryShards(anyString())).thenReturn(perIndexPrimaryShardCount);
        when(node.getNodeId()).thenReturn("test-node");

        assertEquals(0, constraints.weight(balancer, node, indexName, constraintWeight));

        perIndexPrimaryShardCount = 2;
        when(node.numPrimaryShards(anyString())).thenReturn(perIndexPrimaryShardCount);
        assertEquals(CONSTRAINT_WEIGHT, constraints.weight(balancer, node, indexName, constraintWeight));

        constraints.updateAllocationConstraint(INDEX_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID, false);
        assertEquals(0, constraints.weight(balancer, node, indexName, constraintWeight));
    }

    /**
     * Test constraint evaluation logic when per index primary shard count constraint is breached.
     */
    public void testGlobalPrimaryShardsConstraint() {
        ShardsBalancer balancer = mock(LocalShardsBalancer.class);
        BalancedShardsAllocator.ModelNode node = mock(BalancedShardsAllocator.ModelNode.class);
        AllocationConstraints constraints = new AllocationConstraints();
        constraints.updateAllocationConstraint(CLUSTER_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID, true);

        final String indexName = "test-index";
        int primaryShardCount = 1;
        float avgPrimaryShardsPerNode = 2f;

        when(balancer.avgPrimaryShardsPerNode()).thenReturn(avgPrimaryShardsPerNode);
        when(node.numPrimaryShards()).thenReturn(primaryShardCount);
        when(node.getNodeId()).thenReturn("test-node");

        assertEquals(0, constraints.weight(balancer, node, indexName, constraintWeight));

        primaryShardCount = 3;
        when(node.numPrimaryShards()).thenReturn(primaryShardCount);
        assertEquals(constraintWeight, constraints.weight(balancer, node, indexName, constraintWeight));

        constraints.updateAllocationConstraint(CLUSTER_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID, false);
        assertEquals(0, constraints.weight(balancer, node, indexName, constraintWeight));
    }

    /**
     * Test constraint evaluation logic when both per index and global primary shard count constraint is breached.
     */
    public void testPrimaryShardsConstraints() {
        ShardsBalancer balancer = mock(LocalShardsBalancer.class);
        BalancedShardsAllocator.ModelNode node = mock(BalancedShardsAllocator.ModelNode.class);
        AllocationConstraints constraints = new AllocationConstraints();
        constraints.updateAllocationConstraint(CLUSTER_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID, true);
        constraints.updateAllocationConstraint(INDEX_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID, true);

        final String indexName = "test-index";
        int perIndexPrimaryShardCount = 1;
        float avgPerIndexPrimaryShardCount = 2;
        int primaryShardCount = 2;
        float avgPrimaryShardsPerNode = 4;

        when(balancer.avgPrimaryShardsPerNode(indexName)).thenReturn(avgPerIndexPrimaryShardCount);
        when(node.numPrimaryShards(indexName)).thenReturn(perIndexPrimaryShardCount);
        when(balancer.avgPrimaryShardsPerNode()).thenReturn(avgPrimaryShardsPerNode);
        when(node.numPrimaryShards()).thenReturn(primaryShardCount);
        when(node.getNodeId()).thenReturn("test-node");

        assertEquals(0, constraints.weight(balancer, node, indexName, constraintWeight));

        // breaching global primary shard count but not per index primary shard count
        primaryShardCount = 5;
        when(node.numPrimaryShards()).thenReturn(primaryShardCount);
        assertEquals(constraintWeight, constraints.weight(balancer, node, indexName, constraintWeight));

        // when per index primary shard count constraint is also breached
        perIndexPrimaryShardCount = 3;
        when(node.numPrimaryShards(indexName)).thenReturn(perIndexPrimaryShardCount);
        assertEquals(CONSTRAINT_WEIGHT + constraintWeight, constraints.weight(balancer, node, indexName, constraintWeight));

        // disable both constraints
        constraints.updateAllocationConstraint(INDEX_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID, false);
        constraints.updateAllocationConstraint(CLUSTER_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID, false);
        assertEquals(0, constraints.weight(balancer, node, indexName, constraintWeight));
    }

    /**
     * Test constraint evaluation logic when with different values of ConstraintMode
     * for IndexShardPerNode constraint satisfied and breached.
     */
    public void testAllConstraints() {
        ShardsBalancer balancer = mock(LocalShardsBalancer.class);
        BalancedShardsAllocator.ModelNode node = mock(BalancedShardsAllocator.ModelNode.class);
        AllocationConstraints constraints = new AllocationConstraints();
        constraints.updateAllocationConstraint(INDEX_SHARD_PER_NODE_BREACH_CONSTRAINT_ID, true);
        constraints.updateAllocationConstraint(INDEX_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID, true);
        constraints.updateAllocationConstraint(CLUSTER_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID, true);

        final String indexName = "test-index";
        int shardCount = randomIntBetween(1, 500);
        float avgPerIndexShardsPerNode = 1.0f + (random().nextFloat()) * 999.0f;

        int perIndexPrimaryShardCount = randomIntBetween(1, shardCount);
        float avgPerIndexPrimaryShardsPerNode = (random().nextFloat()) * avgPerIndexShardsPerNode;

        float avgPrimaryShardsPerNode = 1.0f + (random().nextFloat()) * 999.0f;
        int primaryShardsPerNode = randomIntBetween(1, shardCount);

        when(balancer.avgPrimaryShardsPerNode(indexName)).thenReturn(avgPerIndexPrimaryShardsPerNode);
        when(node.numPrimaryShards(indexName)).thenReturn(perIndexPrimaryShardCount);

        when(balancer.avgPrimaryShardsPerNode()).thenReturn(avgPrimaryShardsPerNode);
        when(node.numPrimaryShards()).thenReturn(primaryShardsPerNode);

        when(balancer.avgShardsPerNode(indexName)).thenReturn(avgPerIndexShardsPerNode);
        when(node.numShards(indexName)).thenReturn(shardCount);
        when(node.getNodeId()).thenReturn("test-node");

        long expectedWeight = (shardCount >= (int) Math.ceil(avgPerIndexShardsPerNode)) ? CONSTRAINT_WEIGHT : 0;
        expectedWeight += perIndexPrimaryShardCount >= (int) Math.ceil(avgPerIndexPrimaryShardsPerNode) ? CONSTRAINT_WEIGHT : 0;
        expectedWeight += primaryShardsPerNode >= (int) Math.ceil(avgPrimaryShardsPerNode) ? constraintWeight : 0;
        assertEquals(expectedWeight, constraints.weight(balancer, node, indexName, constraintWeight));
    }

    public void testAllocationWeightMatchesLegacyEvaluation() {
        ShardsBalancer balancer = mock(LocalShardsBalancer.class);
        BalancedShardsAllocator.ModelNode node = mock(BalancedShardsAllocator.ModelNode.class);
        when(balancer.avgShardsPerNode("index")).thenReturn(2.25f);
        when(balancer.avgPrimaryShardsPerNode("index")).thenReturn(1.25f);
        when(balancer.avgPrimaryShardsPerNode()).thenReturn(2.25f);
        AllocationConstraints constraints = new AllocationConstraints();
        Map<String, Constraint> legacy = legacyConstraints(false, 0.0f);
        String[] ids = {
            INDEX_SHARD_PER_NODE_BREACH_CONSTRAINT_ID,
            INDEX_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID,
            CLUSTER_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID };

        for (int counts = 0; counts < 8; counts++) {
            when(node.numShards("index")).thenReturn((counts & 1) == 0 ? 1 : 4);
            when(node.numPrimaryShards("index")).thenReturn((counts & 2) == 0 ? 1 : 4);
            when(node.numPrimaryShards()).thenReturn((counts & 4) == 0 ? 1 : 8);
            for (int enabled = 0; enabled < 8; enabled++) {
                for (int i = 0; i < ids.length; i++) {
                    boolean enable = (enabled & (1 << i)) != 0;
                    constraints.updateAllocationConstraint(ids[i], enable);
                    legacy.get(ids[i]).setEnable(enable);
                }
                for (long threshold : thresholdWeights()) {
                    long expected = new Constraint.ConstraintParams(balancer, node, "index", threshold).weight(legacy);
                    assertEquals(expected, constraints.weight(balancer, node, "index", threshold));
                }
            }
        }
    }

    public void testRebalanceWeightMatchesLegacyEvaluation() {
        ShardsBalancer balancer = mock(LocalShardsBalancer.class);
        BalancedShardsAllocator.ModelNode node = mock(BalancedShardsAllocator.ModelNode.class);
        when(balancer.avgPrimaryShardsPerNode("index")).thenReturn(1.25f);
        when(balancer.avgPrimaryShardsPerNode()).thenReturn(2.25f);
        String[] ids = { INDEX_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID, CLUSTER_PRIMARY_SHARD_REBALANCE_CONSTRAINT_ID };

        for (float buffer : new float[] { 0.0f, 0.1f, 1.0f }) {
            RebalanceConstraints constraints = new RebalanceConstraints(new RebalanceParameter(buffer));
            Map<String, Constraint> legacy = legacyConstraints(true, buffer);
            for (int counts = 0; counts < 4; counts++) {
                when(node.numPrimaryShards("index")).thenReturn((counts & 1) == 0 ? 1 : 4);
                when(node.numPrimaryShards()).thenReturn((counts & 2) == 0 ? 1 : 4);
                for (int enabled = 0; enabled < 4; enabled++) {
                    for (int i = 0; i < ids.length; i++) {
                        boolean enable = (enabled & (1 << i)) != 0;
                        constraints.updateRebalanceConstraint(ids[i], enable);
                        legacy.get(ids[i]).setEnable(enable);
                    }
                    for (long threshold : thresholdWeights()) {
                        long expected = new Constraint.ConstraintParams(balancer, node, "index", threshold).weight(legacy);
                        assertEquals(expected, constraints.weight(balancer, node, "index", threshold));
                    }
                }
            }
        }
    }

    public void testNodePredicateSupportsLegacyEvaluation() {
        ShardsBalancer balancer = mock(LocalShardsBalancer.class);
        BalancedShardsAllocator.ModelNode node = mock(BalancedShardsAllocator.ModelNode.class);
        AtomicInteger evaluations = new AtomicInteger();
        Constraint constraint = Constraint.fromNodePredicate((actualBalancer, actualNode, index) -> {
            assertSame(balancer, actualBalancer);
            assertSame(node, actualNode);
            assertEquals("index", index);
            evaluations.incrementAndGet();
            return true;
        });
        Constraint.ConstraintParams params = new Constraint.ConstraintParams(balancer, node, "index", 23L);

        assertFalse(constraint.test(balancer, node, "index", 23L));
        assertFalse(constraint.test(params));
        assertEquals(0, evaluations.get());
        constraint.setEnable(true);
        assertTrue(constraint.test(balancer, node, "index", 23L));
        assertTrue(constraint.test(params));
        assertTrue(constraint.and(p -> p == params).test(params));
        assertFalse(constraint.negate().test(params));
        assertEquals(4, evaluations.get());
        assertEquals(23L, params.weight(Map.of(CLUSTER_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID, constraint)));
        constraint.setEnable(false);
        assertFalse(constraint.test(balancer, node, "index", 23L));
        assertEquals(0L, params.weight(Map.of(CLUSTER_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID, constraint)));
        assertEquals(5, evaluations.get());
    }

    public void testLegacyPredicateAndOverrideRemainSupported() {
        ShardsBalancer balancer = mock(LocalShardsBalancer.class);
        BalancedShardsAllocator.ModelNode node = mock(BalancedShardsAllocator.ModelNode.class);
        Constraint constraint = new Constraint(params -> {
            assertSame(balancer, params.getBalancer());
            assertSame(node, params.getNode());
            assertEquals("index", params.getIndex());
            return true;
        });
        constraint.setEnable(true);
        assertTrue(constraint.test(balancer, node, "index", 23L));
        constraint.setEnable(false);
        assertFalse(constraint.test(balancer, node, "index", 23L));

        Constraint overridden = new Constraint(params -> false) {
            @Override
            public boolean test(ConstraintParams params) {
                assertSame(balancer, params.getBalancer());
                assertSame(node, params.getNode());
                assertEquals("index", params.getIndex());
                return true;
            }
        };
        assertTrue(overridden.test(balancer, node, "index", 23L));
        Constraint.ConstraintParams params = new Constraint.ConstraintParams(balancer, node, "index", 23L);
        assertEquals(CONSTRAINT_WEIGHT, params.weight(Map.of("custom", overridden)));
    }

    private static long[] thresholdWeights() {
        return new long[] {
            Long.MIN_VALUE,
            -1L,
            0L,
            10L,
            23L,
            CONSTRAINT_WEIGHT,
            (1L << 53) - 1,
            (1L << 53) + 1,
            (1L << 60) + 1,
            Long.MAX_VALUE - CONSTRAINT_WEIGHT,
            Long.MAX_VALUE };
    }

    private static Map<String, Constraint> legacyConstraints(boolean rebalance, float buffer) {
        Map<String, Constraint> constraints = new HashMap<>();
        if (rebalance == false) {
            constraints.put(
                INDEX_SHARD_PER_NODE_BREACH_CONSTRAINT_ID,
                new Constraint(
                    params -> params.getNode().numShards(params.getIndex()) >= (int) Math.ceil(
                        params.getBalancer().avgShardsPerNode(params.getIndex())
                    )
                )
            );
        }
        constraints.put(
            INDEX_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID,
            new Constraint(
                params -> params.getNode().numPrimaryShards(params.getIndex()) >= (int) Math.ceil(
                    params.getBalancer().avgPrimaryShardsPerNode(params.getIndex())
                )
            )
        );
        constraints.put(
            rebalance ? CLUSTER_PRIMARY_SHARD_REBALANCE_CONSTRAINT_ID : CLUSTER_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID,
            new Constraint(
                params -> params.getNode().numPrimaryShards() >= (int) Math.ceil(
                    params.getBalancer().avgPrimaryShardsPerNode() * (1 + buffer)
                )
            )
        );
        return constraints;
    }

}
