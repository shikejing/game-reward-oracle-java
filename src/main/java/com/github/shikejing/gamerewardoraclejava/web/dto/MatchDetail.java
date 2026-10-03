package com.github.shikejing.gamerewardoraclejava.web.dto;

import com.github.shikejing.gamerewardoraclejava.common.MatchStatus;
import com.github.shikejing.gamerewardoraclejava.common.PlayerResult;

import java.time.Instant;
import java.util.List;

public record MatchDetail(String matchId,
                          MatchStatus status,
                          long currentSeq,
                          Long settledSeq,
                          Instant settledAt,
                          Instant revisionDeadline,
                          Instant distributedAt,
                          List<PlayerResult> players,
                          long reportCount,
                          long settlementCount) {
}
