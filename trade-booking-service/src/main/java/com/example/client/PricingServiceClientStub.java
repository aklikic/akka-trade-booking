package com.example.client;

import com.example.domain.CreditStatus;
import com.example.domain.Instrument;
import com.example.domain.Quota;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

public class PricingServiceClientStub implements PricingServiceClient {

  private static final Logger logger = LoggerFactory.getLogger(PricingServiceClientStub.class);

  @Override
  public Optional<Quota> getQuota(String clientId, String priceRateId, String quotaId) {
    logger.info("Stub getQuota: clientId={}, priceRateId={}", clientId, priceRateId);
    return Optional.of(new Quota(
            quotaId,
        priceRateId,
        clientId,
        new Instrument("EURUSD", "SPOT"),
        1.1050,
        1.1055,
        CreditStatus.OK,
        System.currentTimeMillis()));
  }
}
