package com.example.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CreditCheckServiceStub implements CreditCheckService {

  private static final Logger logger = LoggerFactory.getLogger(CreditCheckServiceStub.class);

  @Override
  public void subscribe(String clientId) {
    logger.info("Stub: subscribe to credit check for client {}", clientId);
  }

  @Override
  public void unsubscribe(String clientId) {
    logger.info("Stub: unsubscribe from credit check for client {}", clientId);
  }
}