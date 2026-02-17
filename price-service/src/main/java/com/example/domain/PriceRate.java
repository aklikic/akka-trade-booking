package com.example.domain;

import java.time.Instant;

public record PriceRate(String priceRateId, String tenor, double bid, double ask, long seq, long timestamp) {
}
