package com.example.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoHedgerServiceClientStub implements AutoHedgerServiceClient {

  private static final Logger logger = LoggerFactory.getLogger(AutoHedgerServiceClientStub.class);

  @Override
  public void submit(HedgeRequest request) {
    logger.info("Stub hedge submitted: tradeId={}, instrument={}, side={}, quantity={}",
        request.tradeId(), request.instrument(), request.side(), request.quantity());
  }
}