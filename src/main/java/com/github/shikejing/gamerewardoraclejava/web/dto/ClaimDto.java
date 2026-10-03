package com.github.shikejing.gamerewardoraclejava.web.dto;

import java.time.Instant;

public record ClaimDto(long id,
                       long distributionId,
                       String matchId,
                       String playerId,
                       long amount,
                       Instant claimedAt) {
}
