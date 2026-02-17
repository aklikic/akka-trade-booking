package com.example.domain;

import akka.javasdk.annotations.TypeName;

public sealed interface QuotaEntityEvent {

  @TypeName("created")
  record Created(
      String quoteId,
      Instrument instrument,
      double bid,
      double ask,
      String settlementDate,
      CreditStatus creditStatus) implements QuotaEntityEvent {}

  @TypeName("accepted")
  record Accepted(String quoteId, String tradeId, String side, double quantity) implements QuotaEntityEvent {}
}