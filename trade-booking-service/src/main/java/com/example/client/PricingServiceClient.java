package com.example.client;

import com.example.domain.Quota;

import java.util.Optional;

public interface PricingServiceClient {

  Optional<Quota> getQuota(String clientId, String priceRateId, String quotaId);
}
