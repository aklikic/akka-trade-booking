package com.example.client;

public interface FxRateService {

  void subscribe(Instrument instrument);

  void unsubscribe(Instrument instrument);
}