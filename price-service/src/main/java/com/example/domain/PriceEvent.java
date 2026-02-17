package com.example.domain;

import akka.javasdk.annotations.TypeName;

import java.time.Instant;
import java.util.List;

public sealed interface PriceEvent {

  @TypeName("subscribed")
  record Subscribed(String clientId) implements PriceEvent {}

  @TypeName("first-subscribed")
  record FirstSubscribed(String ccyPair) implements PriceEvent {}

  @TypeName("unsubscribed")
  record Unsubscribed(String clientId) implements PriceEvent {}

  @TypeName("all-unsubscribed")
  record AllUnsubscribed(String ccyPair) implements PriceEvent {}

  @TypeName("price-rate-added")
  record PriceRateAdded(String ccyPair, PriceRate priceRate, List<PriceRateClientQuota> quotas) implements PriceEvent {}


}