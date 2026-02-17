package com.example.domain;

public record TradeBookingState(
    Quota quota,
    String tradeId,
    String side,
    double quantity,
    PreTradeResult preTradeResult,
    TradeStatus status) {

  public enum PreTradeResult {
    OK,
    CREDIT_CHECK_FAILED,
    CREDIT_STATUS_UNKNOWN
  }

  public static TradeBookingState initial(String tradeId, Quota quota, String side, double quantity) {
    return new TradeBookingState(quota, tradeId, side, quantity, null, TradeStatus.PENDING);
  }

  public static PreTradeResult validateCredit(CreditStatus creditStatus) {
    return switch (creditStatus) {
      case OK -> PreTradeResult.OK;
      case FAIL -> PreTradeResult.CREDIT_CHECK_FAILED;
      case UNKNOWN -> PreTradeResult.CREDIT_STATUS_UNKNOWN;
    };
  }

  public TradeBookingState withPreTradeCheck( PreTradeResult result) {
    var newStatus = result == PreTradeResult.OK ? TradeStatus.PRE_TRADE_CHECK : TradeStatus.REJECTED;
    return new TradeBookingState(quota, tradeId, side, quantity, result, newStatus);
  }

  public TradeBookingState withHedging() {
    return new TradeBookingState(quota, tradeId, side, quantity, preTradeResult, TradeStatus.HEDGING);
  }

  public TradeBookingState withConfirmed() {
    return new TradeBookingState(quota, tradeId, side, quantity, preTradeResult, TradeStatus.CONFIRMED);
  }

  public TradeBookingState withRejected(PreTradeResult reason) {
    return new TradeBookingState(quota, tradeId, side, quantity, reason, TradeStatus.REJECTED);
  }

  public boolean isRejected() {
    return status == TradeStatus.REJECTED;
  }

  public boolean isConfirmed() {
    return status == TradeStatus.CONFIRMED;
  }

  public boolean isTerminal() {
    return isRejected() || isConfirmed();
  }
}