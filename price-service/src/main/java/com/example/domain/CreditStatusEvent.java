package com.example.domain;

public record CreditStatusEvent(String clientId, CreditStatus status, String reason, long tsMs) {}