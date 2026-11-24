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

import java.util.HashMap;
import java.util.Map;

import static org.opensearch.cluster.routing.allocation.ConstraintTypes.CLUSTER_PRIMARY_SHARD_REBALANCE_CONSTRAINT_ID;
import static org.opensearch.cluster.routing.allocation.ConstraintTypes.INDEX_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID;
import static org.opensearch.cluster.routing.allocation.ConstraintTypes.isPrimaryShardsPerNodeBreached;
import static org.opensearch.cluster.routing.allocation.ConstraintTypes.predicateKeyToWeightMap;

/**
 * Constraints applied during rebalancing round; specify conditions which, if breached, reduce the
 * priority of a node for receiving shard relocations.
 *
 * @opensearch.internal
 */
public class RebalanceConstraints {

    private final Map<String, Constraint> constraints;
    private final String[] constraintIds;
    private final Constraint[] orderedConstraints;

    public RebalanceConstraints(RebalanceParameter rebalanceParameter) {
        this.constraints = new HashMap<>();
        this.constraints.put(
            INDEX_PRIMARY_SHARD_BALANCE_CONSTRAINT_ID,
            Constraint.fromNodePredicate(ConstraintTypes::isPerIndexPrimaryShardsPerNodeBreached)
        );
        float buffer = rebalanceParameter.getPreferPrimaryBalanceBuffer();
        this.constraints.put(
            CLUSTER_PRIMARY_SHARD_REBALANCE_CONSTRAINT_ID,
            Constraint.fromNodePredicate((balancer, node, index) -> isPrimaryShardsPerNodeBreached(balancer, node, buffer))
        );
        // Preserve HashMap order because double-to-long accumulation is order-sensitive at large thresholds.
        this.constraintIds = new String[constraints.size()];
        this.orderedConstraints = new Constraint[constraints.size()];
        int i = 0;
        for (Map.Entry<String, Constraint> entry : constraints.entrySet()) {
            constraintIds[i] = entry.getKey();
            orderedConstraints[i++] = entry.getValue();
        }
    }

    public void updateRebalanceConstraint(String constraint, boolean enable) {
        this.constraints.get(constraint).setEnable(enable);
    }

    public long weight(ShardsBalancer balancer, BalancedShardsAllocator.ModelNode node, String index, long primaryConstraintThreshold) {
        long totalConstraintWeight = 0;
        for (int i = 0; i < orderedConstraints.length; i++) {
            if (orderedConstraints[i].test(balancer, node, index, primaryConstraintThreshold)) {
                double weight = predicateKeyToWeightMap(constraintIds[i], primaryConstraintThreshold);
                totalConstraintWeight += weight;
            }
        }
        return totalConstraintWeight;
    }
}
