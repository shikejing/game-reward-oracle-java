package com.github.shikejing.gamerewardoraclejava.web.dto;

import java.time.Instant;

public record DistributionDto(long id,
                              String matchId,
                              long settlementId,
                              Instant distributedAt,
                              long totalAmount,
                              int recordCount) {
}
