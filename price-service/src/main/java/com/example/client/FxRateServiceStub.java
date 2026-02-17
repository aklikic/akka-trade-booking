package com.example.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FxRateServiceStub implements FxRateService {

  private static final Logger logger = LoggerFactory.getLogger(FxRateServiceStub.class);

  @Override
  public void subscribe(Instrument instrument) {
    logger.info("Stub: subscribe to FX rates for {}", instrument);
  }

  @Override
  public void unsubscribe(Instrument instrument) {
    logger.info("Stub: unsubscribe from FX rates for {}", instrument);
  }
}