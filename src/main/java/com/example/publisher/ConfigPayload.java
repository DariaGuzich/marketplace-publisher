package com.example.publisher;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/** Колонка outbox.payload (JSONB), формат задаёт marketplace-api. */
public record ConfigPayload(
        @JsonProperty("floor_price") BigDecimal floorPrice,
        @JsonProperty("currency") String currency,
        @JsonProperty("blocked_domains") List<String> blockedDomains) {
}
