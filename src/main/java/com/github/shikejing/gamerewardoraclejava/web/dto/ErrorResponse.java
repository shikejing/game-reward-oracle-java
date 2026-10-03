package com.github.shikejing.gamerewardoraclejava.web.dto;

import java.time.Instant;

public record ErrorResponse(String errorCode, String message, Instant timestamp) {
}
