package com.example.domain;

public record Quota(
    String quotaId,
    String priceRateId,
    String clientId,
    Instrument instrument,
    double bid,
    double ask,
    CreditStatus creditStatus,
    long timestamp) {
}