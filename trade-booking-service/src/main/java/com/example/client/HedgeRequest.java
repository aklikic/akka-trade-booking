package com.example.client;

import com.example.domain.Instrument;

public record HedgeRequest(String tradeId, Instrument instrument, String side, double quantity) {
}