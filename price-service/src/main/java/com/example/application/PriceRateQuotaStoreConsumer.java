package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import akka.stream.Materializer;
import com.example.domain.PriceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Component(id = "price-rate-quota-store-consumer")
@Consume.FromEventSourcedEntity(PriceEntity.class)
public class PriceRateQuotaStoreConsumer extends Consumer {

  private static final Logger logger = LoggerFactory.getLogger(PriceRateQuotaStoreConsumer.class);

  private final ComponentClient componentClient;
  private final Materializer materializer;

  public PriceRateQuotaStoreConsumer(ComponentClient componentClient, Materializer materializer) {
    this.componentClient = componentClient;
    this.materializer = materializer;
  }

  public Effect onEvent(PriceEvent event) {
    return switch (event) {
      case PriceEvent.PriceRateAdded e -> {
        logger.info("Price rate priceRateId {} added for {}",e.priceRate().priceRateId(), e.ccyPair());
        boolean isEventLocal = messageContext().originRegion().isPresent()?messageContext().originRegion().get().equals(messageContext().selfRegion()):true;
        // only do this for a consumer that is in the same region where the event originated
        if(isEventLocal) {
          if (!e.quotas().isEmpty()) {
            componentClient.forKeyValueEntity(e.priceRate().priceRateId())
                    .method(QuotaEntity::add)
                    .invoke(new QuotaEntity.AddCommand(e.ccyPair(), e.priceRate(), e.quotas()));
          }
        }


        yield effects().done();
      }
      default -> effects().ignore();
    };
  }
}