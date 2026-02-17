package com.example.domain;

public record Quota(
    String quotaId,
    String priceRateId,
    String clientId,
    String ccyPair,
    String tenor,
    double bid,
    double ask,
    CreditStatus creditStatus,
    long timestamp) {
}