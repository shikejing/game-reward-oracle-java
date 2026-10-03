package com.github.shikejing.reward.domain;

import java.util.List;

/** 领域模型骨架：仅类型定义，语义在后续步骤填充。 */
public final class Model {
    private Model() {}

    /** 对局状态机：已上报 -> 已结算 -> 已发放；撤销仅作为报告标记。 */
    public enum MatchStatus { REPORTED, SETTLED, GRANTED }

    public enum TiePolicy { EACH_FULL, SPLIT_SUM }

    public enum ClaimFailure { NOT_FOUND, FORBIDDEN, NOT_GRANTED }

    public record PlayerScore(String playerId, long score) {}

    /** 预言机上报：matchId + reportSeq 唯一标识一次上报。 */
    public record MatchReport(String matchId, long reportSeq, List<PlayerScore> scores,
                              long receivedAtMillis, boolean revoked) {}

    public record RewardLine(String playerId, int rank, long amount) {}

    public record Settlement(String matchId, long reportSeq, List<RewardLine> lines,
                             long settledAtMillis) {}

    public record GrantRecord(String matchId, String playerId, long amount,
                              long grantedAtMillis) {}

    public record ClaimRecord(String matchId, String playerId, long amount,
                              long claimedAtMillis) {}
}
