package com.github.shikejing.gamerewardoraclejava.common;

/**
 * 奖励记录状态。
 */
public enum RewardRecordStatus {
    /** 已结算，待发放 */
    SETTLED,
    /** 已被新结算取代（修订历史保留） */
    SUPERSEDED,
    /** 已发放，待领取 */
    DISTRIBUTED,
    /** 已领取 */
    CLAIMED
}
