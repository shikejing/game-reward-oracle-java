package com.github.shikejing.gamerewardoraclejava.web.dto;

import java.time.Instant;

public record DistributeResponse(long distributionId,
                                 String matchId,
                                 long settlementId,
                                 Instant distributedAt,
                                 long totalAmount,
                                 int recordCount,
                                 boolean alreadyExisted) {
}
