/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.routing.allocation;

import org.opensearch.cluster.routing.allocation.allocator.BalancedShardsAllocator;
import org.opensearch.cluster.routing.allocation.allocator.ShardsBalancer;

import java.util.function.Predicate;

/**
 * Defines different constraints definitions
 *
 * @opensearch.internal
 */
public class ConstraintTypes {
    public final static long CONSTRAINT_WEIGHT = 1000000L;

    /**
     * Defines per index constraint which is breached when a node contains more than avg number of primary shards for an index
     */
    public final static String INDEX_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID = "index.primary.shard.balance.constraint";

    /**
     * Defines a cluster constraint which is breached when a node contains more than avg primary shards across all indices
     */
    public final static String CLUSTER_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID = "cluster.primary.shard.balance.constraint";

    /**
     * Defines a cluster constraint which is breached when a node contains more than avg primary shards across all indices
     */
    public final static String CLUSTER_PRIMARY_SHARD_REBALANCE_CONSTRAINT_ID = "cluster.primary.shard.rebalance.constraint";

    /**
     * Defines an index constraint which is breached when a node contains more than avg number of shards for an index
     */
    public final static String INDEX_SHARD_PER_NODE_BREACH_CONSTRAINT_ID = "index.shard.count.constraint";

    /**
     * Constraint to control number of shards of an index allocated on a single
     * node.
     * <p>
     * In current weight function implementation, when a node has significantly
     * fewer shards than other nodes (e.g. during single new node addition or node
     * replacement), its weight is much less than other nodes. All shard allocations
     * at this time tend to land on the new node with skewed weight. This breaks
     * index level balance in the cluster, by creating all shards of the same index
     * on one node, often resulting in a hotspot on that node.
     * <p>
     * This constraint is breached when balancer attempts to allocate more than
     * average shards per index per node.
     */
    public static Predicate<Constraint.ConstraintParams> isIndexShardsPerNodeBreached() {
        return params -> isIndexShardsPerNodeBreached(params.getBalancer(), params.getNode(), params.getIndex());
    }

    static boolean isIndexShardsPerNodeBreached(ShardsBalancer balancer, BalancedShardsAllocator.ModelNode node, String index) {
        int currIndexShardsOnNode = node.numShards(index);
        int allowedIndexShardsPerNode = (int) Math.ceil(balancer.avgShardsPerNode(index));
        return currIndexShardsOnNode >= allowedIndexShardsPerNode;
    }

    /**
     * Defines a predicate which returns true when specific to an index, a node contains more than average number of primary
     * shards. This constraint is used in weight calculation during allocation and rebalancing. When breached a high weight
     * {@link ConstraintTypes#CONSTRAINT_WEIGHT} is assigned to node resulting in lesser chances of node being selected
     * as allocation or rebalancing target
     */
    public static Predicate<Constraint.ConstraintParams> isPerIndexPrimaryShardsPerNodeBreached() {
        return params -> isPerIndexPrimaryShardsPerNodeBreached(params.getBalancer(), params.getNode(), params.getIndex());
    }

    static boolean isPerIndexPrimaryShardsPerNodeBreached(ShardsBalancer balancer, BalancedShardsAllocator.ModelNode node, String index) {
        int perIndexPrimaryShardCount = node.numPrimaryShards(index);
        int perIndexAllowedPrimaryShardCount = (int) Math.ceil(balancer.avgPrimaryShardsPerNode(index));
        return perIndexPrimaryShardCount >= perIndexAllowedPrimaryShardCount;
    }

    /**
     * Defines a predicate which returns true when a node contains more than average number of primary shards with added buffer. This
     * constraint is used in weight calculation during allocation/rebalance both. When breached a high weight {@link ConstraintTypes#CONSTRAINT_WEIGHT}
     * is assigned to node resulting in lesser chances of node being selected as allocation/rebalance target
     */
    public static Predicate<Constraint.ConstraintParams> isPrimaryShardsPerNodeBreached(float buffer) {
        return params -> isPrimaryShardsPerNodeBreached(params.getBalancer(), params.getNode(), buffer);
    }

    static boolean isPrimaryShardsPerNodeBreached(ShardsBalancer balancer, BalancedShardsAllocator.ModelNode node, float buffer) {
        int primaryShardCount = node.numPrimaryShards();
        int allowedPrimaryShardCount = (int) Math.ceil(balancer.avgPrimaryShardsPerNode() * (1 + buffer));
        return primaryShardCount >= allowedPrimaryShardCount;
    }

    public static long predicateKeyToWeightMap(String key, long primaryConstraintWeight) {
        switch (key) {
            case CLUSTER_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID:
            case CLUSTER_PRIMARY_SHARD_REBALANCE_CONSTRAINT_ID:
                return primaryConstraintWeight;
            default:
                return CONSTRAINT_WEIGHT;
        }
    }
}
