package com.github.shikejing.gamerewardoraclejava.web.dto;

import java.time.Instant;
import java.util.List;

public record SettleResponse(String matchId,
                             long seq,
                             long settlementId,
                             String status,
                             Instant settledAt,
                             long totalAmount,
                             List<RewardRecordDto> records) {
}
