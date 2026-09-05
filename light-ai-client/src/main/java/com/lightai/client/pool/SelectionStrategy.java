package com.lightai.client.pool;

/** 凭证池选择策略（DATABASE_PLAN credential_pool.selection_strategy）。 */
public enum SelectionStrategy {
    LEAST_CONCURRENT,
    ROUND_ROBIN,
    WEIGHTED_RANDOM
}
