package com.example.domain;

public record TradeNotification(
    String quotaId,
    String tradeId,
    String side,
    double quantity,
    TradeBookingState.PreTradeResult preTradeResult,
    TradeStatus status) {

  public static TradeNotification confirmed(String tradeId, String quotaId, String side, double quantity) {
    return new TradeNotification(quotaId, tradeId, side, quantity, TradeBookingState.PreTradeResult.OK, TradeStatus.CONFIRMED);
  }

  public static TradeNotification rejected(String tradeId, String quotaId, TradeBookingState.PreTradeResult reason) {
    return new TradeNotification(quotaId, tradeId, null, 0, reason, TradeStatus.REJECTED);
  }
}