package com.example.client;

import com.example.domain.CreditStatus;
import com.example.domain.Instrument;
import com.example.domain.Quota;
import akka.javasdk.http.HttpClient;
import akka.javasdk.http.HttpClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

public class PricingServiceClientImpl implements PricingServiceClient {

  private static final Logger logger = LoggerFactory.getLogger(PricingServiceClientImpl.class);

  record PriceServiceQuota(
      String quotaId,
      String priceRateId,
      String clientId,
      String ccyPair,
      String tenor,
      double bid,
      double ask,
      CreditStatus creditStatus,
      long timestamp) {

    Quota toQuota() {
      return new Quota(quotaId, priceRateId, clientId, new Instrument(ccyPair, tenor), bid, ask, creditStatus, timestamp);
    }
  }

  private final HttpClient httpClient;

  public PricingServiceClientImpl(HttpClientProvider httpClientProvider) {
    this.httpClient = httpClientProvider.httpClientFor("price-service");
  }

  @Override
  public Optional<Quota> getQuota(String clientId, String priceRateId, String quotaId) {
    logger.info("Fetching quota from price-service: clientId={}, priceRateId={}, quotaId={quotaId}", clientId, priceRateId, quotaId);

    var response = httpClient
        .GET("/clients/" + clientId + "/price-rate/" + priceRateId + "/quota")
        .responseBodyAs(PriceServiceQuota.class)
        .invoke();

    if (!response.status().isSuccess() || response.body() == null) {
      logger.warn("Quota not found: clientId={}, priceRateId={}, status={}", clientId, priceRateId, response.status());
      return Optional.empty();
    }
    var quota = response.body().toQuota();
    logger.info("Quota from price-service: clientId={}, priceRateId={}, quota:{}", clientId, priceRateId,quota);
    return Optional.ofNullable(quota);
  }
}
