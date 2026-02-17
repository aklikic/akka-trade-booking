package com.example.domain;

public record QuotaEntityState(
    String quoteId,
    Instrument instrument,
    double bid,
    double ask,
    String settlementDate,
    CreditStatus creditStatus,
    String tradeId,
    String side,
    double quantity,
    QuotaStatus status) {

  public static QuotaEntityState from(QuotaEntityEvent.Created event) {
    return new QuotaEntityState(
        event.quoteId(),
        event.instrument(),
        event.bid(),
        event.ask(),
        event.settlementDate(),
        event.creditStatus(),
        null,
        null,
        0,
        QuotaStatus.CREATED);
  }

  public QuotaEntityState withAccepted(String tradeId, String side, double quantity) {
    return new QuotaEntityState(quoteId, instrument, bid, ask, settlementDate, creditStatus, tradeId, side, quantity, QuotaStatus.ACCEPTED);
  }

  public boolean isCreated() {
    return status == QuotaStatus.CREATED;
  }

  public boolean isAccepted() {
    return status == QuotaStatus.ACCEPTED;
  }
}