package com.github.shikejing.gamerewardoraclejava.web.dto;

import com.github.shikejing.gamerewardoraclejava.common.SettlementStatus;

import java.time.Instant;
import java.util.List;

public record SettlementDto(long id,
                            long seq,
                            SettlementStatus status,
                            Instant settledAt,
                            long totalAmount,
                            List<RewardRecordDto> records) {
}
