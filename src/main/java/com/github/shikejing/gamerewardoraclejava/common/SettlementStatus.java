package com.github.shikejing.gamerewardoraclejava.common;

/**
 * 结算单状态。
 */
public enum SettlementStatus {
    /** 当前生效 */
    ACTIVE,
    /** 已被新结算取代（修订历史保留） */
    SUPERSEDED,
    /** 已发放 */
    DISTRIBUTED
}
