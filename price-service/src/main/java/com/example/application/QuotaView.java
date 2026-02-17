package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import com.example.domain.*;

import java.util.List;

@Component(id = "quota-view")
public class QuotaView extends View {

  public record QuotaEntry(String ccyPair, PriceRate priceRate, List<PriceRateClientQuota> quotas) {}

  @Consume.FromEventSourcedEntity(PriceEntity.class)
  public static class QuotaUpdater extends TableUpdater<QuotaEntry> {

    public Effect<QuotaEntry> onEvent(PriceEvent event) {
      return switch (event) {
        case PriceEvent.PriceRateAdded e ->
                effects().updateRow(new QuotaEntry(e.ccyPair(), e.priceRate(), e.quotas()));
        default -> effects().ignore();
      };
    }
  }
  @Query(value = "SELECT * FROM quotas", streamUpdates = true)
  public View.QueryStreamEffect<QuotaEntry> streamAll() {
    return queryStreamResult();
  }
}