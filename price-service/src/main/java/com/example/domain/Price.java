package com.example.domain;

import java.util.*;

public record Price(String ccyPair, List<String> subscriptions, Optional<PriceRate> lastPriceRate) {

  public Price withPriceRate(PriceRate lastPriceRate) {
    return new Price(ccyPair, subscriptions, Optional.ofNullable(lastPriceRate));
  }

  public Price withSubscription(String clientId) {
    var updated = new ArrayList<>(subscriptions);
    updated.add(clientId);
    return new Price(ccyPair, List.copyOf(updated), lastPriceRate);
  }

  public Price withoutSubscription(String clientId) {
    var updated = new ArrayList<>(subscriptions);
    updated.remove(clientId);
    return new Price(ccyPair, List.copyOf(updated), lastPriceRate);
  }

  public boolean isSubscribed(String clientId) {
    return subscriptions.contains(clientId);
  }

  public boolean hasSubscriptions() {
    return !subscriptions.isEmpty();
  }

  public boolean isDuplicateRate(double bid, double ask) {
    return lastPriceRate
            .map(q -> q.bid() == bid && q.ask() == ask)
            .orElse(false);
  }
}