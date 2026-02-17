package com.example.domain;

import java.time.Instant;

public record QuotaCreated(
    String quotaId,
    String clientId,
    String ccyPair,
    String tenor,
    double bid,
    double ask,
    CreditStatus creditStatus,
    Instant timestamp) {}