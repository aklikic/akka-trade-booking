package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import akka.stream.Materializer;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import com.example.domain.FxRateEvent;
import com.example.domain.PriceRateClientQuota;
import com.example.domain.Quota;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

@Component(id = "fx-rate-consumer")
@Consume.FromTopic("fx-rate-events")
public class FxRateConsumer extends Consumer {

  private static final Logger logger = LoggerFactory.getLogger(FxRateConsumer.class);

  private final ComponentClient componentClient;
  private final Materializer materializer;

  public FxRateConsumer(ComponentClient componentClient, Materializer materializer) {
    this.componentClient = componentClient;
    this.materializer = materializer;
  }

  public Effect onEvent(FxRateEvent event) {
    priceRateProcessing(componentClient, materializer,  event.instrument().ccyPair(), event.instrument().tenor(),  event.bid(),  event.ask(), event.seq(),  event.tsMs(), Optional.empty());
    return effects().done();
  }

  public static void priceRateProcessing(ComponentClient componentClient, Materializer materializer, String ccyPair, String tenor, double bid, double ask, long seq, long tsMs, Optional<String> priceRateId) {
    logger.info("Received FX rate event for {} tenor {} bid {} ask {} maybePriceRateId {}", ccyPair, tenor,  bid,  ask, priceRateId);
    var subscriptions = componentClient.forEventSourcedEntity(ccyPair)
            .method(PriceEntity::getSubscriptions)
            .invoke();
    var quotas = componentClient.forView()
            .stream(ClientView::getByClientIds)
            .source(subscriptions)
            .map(entry -> new PriceRateClientQuota(UUID.randomUUID().toString(), entry.clientId(), entry.creditStatus()))
            .toMat(Sink.seq(), Keep.right())
            .run(materializer)
            .toCompletableFuture()
            .join();

    if(!quotas.isEmpty()) {
      componentClient.forEventSourcedEntity(ccyPair)
              .method(PriceEntity::priceRateUpdate)
              .invoke(new PriceEntity.PriceRateUpdate(tenor, bid, ask, seq, tsMs, quotas, priceRateId));
    }
  }
}