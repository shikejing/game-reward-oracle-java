package com.github.shikejing.gamerewardoraclejava.common;

/**
 * 对局状态。
 */
public enum MatchStatus {
    /** 已上报，尚未结算 */
    REPORTED,
    /** 已结算，修订窗口开放中 */
    SETTLED,
    /** 修订窗口已关闭，结算最终化（仍可发放） */
    REVISION_CLOSED,
    /** 已发放，奖励对外发布，不可撤销 */
    DISTRIBUTED
}
