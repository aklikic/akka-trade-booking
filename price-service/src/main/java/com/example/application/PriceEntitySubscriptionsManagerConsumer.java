package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import com.example.client.FxRateService;
import com.example.client.Instrument;
import com.example.domain.PriceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(id = "price-entity-subscriptions-manager-consumer")
@Consume.FromEventSourcedEntity(PriceEntity.class)
public class PriceEntitySubscriptionsManagerConsumer extends Consumer {

  private static final Logger logger = LoggerFactory.getLogger(PriceEntitySubscriptionsManagerConsumer.class);

  private final FxRateService fxRateService;
  private final ComponentClient componentClient;

  public PriceEntitySubscriptionsManagerConsumer(FxRateService fxRateService,  ComponentClient componentClient) {
    this.fxRateService = fxRateService;
    this.componentClient = componentClient;
  }

  public Effect onEvent(PriceEvent event) {
    return switch (event) {
      case PriceEvent.FirstSubscribed e -> {
        logger.info("First subscriber for {}, subscribing to FX rate service", e.ccyPair());
        fxRateService.subscribe(new Instrument(e.ccyPair(), "SPOT"));
        yield effects().done();
      }
      case PriceEvent.AllUnsubscribed e -> {
        logger.info("All unsubscribed for {}, unsubscribing from FX rate service", e.ccyPair());
        fxRateService.unsubscribe(new Instrument(e.ccyPair(), "SPOT"));
        yield effects().done();
      }
      default -> effects().ignore();
    };
  }
}