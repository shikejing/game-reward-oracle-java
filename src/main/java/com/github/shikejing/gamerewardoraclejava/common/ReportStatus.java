package com.github.shikejing.gamerewardoraclejava.common;

/**
 * 上报处理结果。
 */
public enum ReportStatus {
    /** 接受并收敛为当前结论 */
    ACCEPTED,
    /** 重复上报（相同 seq），幂等返回 */
    DUPLICATE,
    /** 迟到上报（seq 小于当前已收敛序号） */
    LATE,
    /** 修订窗口已关闭，拒绝 */
    REVISION_CLOSED,
    /** 已发放，拒绝修订 */
    ALREADY_DISTRIBUTED,
    /** 校验未通过 */
    REJECTED
}
