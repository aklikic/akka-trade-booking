package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import com.example.domain.TradeBookingState;

import java.util.Collection;

@Component(id = "trades-by-client-view")
public class TradesByClientView extends View {

  public record TradeEntry(
      String tradeId,
      String clientId,
      String ccyPair,
      String side,
      double quantity,
      String status,
      String preTradeResult) {}

  public record TradeEntries(Collection<TradeEntry> entries) {}

  @Consume.FromWorkflow(TradeBookingWorkflow.class)
  public static class TradesUpdater extends TableUpdater<TradeEntry> {

    public Effect<TradeEntry> onUpdate(TradeBookingState state) {
      return effects().updateRow(new TradeEntry(
          state.tradeId(),
          state.quota().clientId(),
          state.quota().instrument().ccyPair(),
          state.side(),
          state.quantity(),
          state.status().name(),
          state.preTradeResult() != null ? state.preTradeResult().name() : ""));
    }
  }

  @Query("SELECT * AS entries FROM trades_by_client WHERE clientId = :clientId")
  public QueryEffect<TradeEntries> getByClientId(String clientId) {
    return queryResult();
  }

  @Query(
      value = "SELECT * FROM trades_by_client WHERE clientId = :clientId",
      streamUpdates = true)
  public QueryStreamEffect<TradeEntry> streamByClientId(String clientId) {
    return queryStreamResult();
  }
}