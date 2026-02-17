package com.example.domain;

import com.example.client.Instrument;

public record FxRateEvent(Instrument instrument, double bid, double ask, long seq, long tsMs) {}