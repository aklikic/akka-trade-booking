package com.example.domain;

import java.util.List;

public record QuotaState(String ccyPair, PriceRate priceRate, List<PriceRateClientQuota> quotas) {

  public static QuotaState empty() {
    return new QuotaState(null,null, List.of());
  }
}