package com.example.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import com.example.domain.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component(id = "price-entity")
public class PriceEntity extends EventSourcedEntity<Price, PriceEvent> {

  private final String entityId;

  public PriceEntity(EventSourcedEntityContext context) {
    this.entityId = context.entityId();
  }

  @Override
  public Price emptyState() {
    return new Price(entityId, List.of(), Optional.empty());
  }

  public record PriceRateUpdate(String tenor, double bid, double ask, long seq, long tsMs, List<PriceRateClientQuota> quotas, Optional<String> priceRateId) {
    public PriceRateUpdate(String tenor, double bid, double ask, long seq, long tsMs, List<PriceRateClientQuota> quotas) {
      this(tenor, bid, ask, seq, tsMs, quotas, Optional.empty());
    }
  }
  public Effect<Done> priceRateUpdate(PriceRateUpdate update) {
    if (!currentState().hasSubscriptions()) {
      return effects().reply(Done.getInstance());
    }
    if (currentState().isDuplicateRate(update.bid(), update.ask())) {
      return effects().reply(Done.getInstance());
    }

    var priceRateId = update.priceRateId().orElse(UUID.randomUUID().toString());
    var priceRate = new PriceRate(priceRateId, update.tenor(), update.bid(), update.ask(), update.seq(), update.tsMs());
    return effects()
            .persist(new PriceEvent.PriceRateAdded(entityId, priceRate, update.quotas()))
            .thenReply(s -> Done.getInstance());
  }

  public Effect<Done> subscribe(String clientId) {
    if (currentState().isSubscribed(clientId)) {
      return effects().reply(Done.getInstance());
    }
    var subscribed = new PriceEvent.Subscribed(clientId);
    if (!currentState().hasSubscriptions()) {
      return effects()
          .persistAll(List.of(subscribed, new PriceEvent.FirstSubscribed(entityId)))
          .thenReply(state -> Done.getInstance());
    }
    return effects()
        .persist(subscribed)
        .thenReply(state -> Done.getInstance());
  }

  public Effect<Done> unsubscribe(String clientId) {
    if (!currentState().isSubscribed(clientId)) {
      return effects().error("Client " + clientId + " is not subscribed");
    }
    var unsubscribed = new PriceEvent.Unsubscribed(clientId);
    if (currentState().subscriptions().size() == 1) {
      return effects()
          .persistAll(List.of(unsubscribed, new PriceEvent.AllUnsubscribed(entityId)))
          .thenReply(state -> Done.getInstance());
    }
    return effects()
        .persist(unsubscribed)
        .thenReply(state -> Done.getInstance());
  }

  public ReadOnlyEffect<List<String>> getSubscriptions() {
    return effects().reply(currentState().subscriptions());
  }
  public ReadOnlyEffect<Optional<PriceRate>> getLastPriceRate() {
    return effects().reply(currentState().lastPriceRate());
  }

  @Override
  public Price applyEvent(PriceEvent event) {
    return switch (event) {
      case PriceEvent.Subscribed e -> currentState().withSubscription(e.clientId());
      case PriceEvent.Unsubscribed e -> currentState().withoutSubscription(e.clientId());
      case PriceEvent.PriceRateAdded e -> currentState().withPriceRate(e.priceRate());
      default -> currentState();
    };
  }
}