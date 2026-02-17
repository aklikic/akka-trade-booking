package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import com.example.domain.CreditStatusEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(id = "credit-check-consumer")
@Consume.FromTopic("credit-check-events")
public class CreditCheckConsumer extends Consumer {

  private static final Logger logger = LoggerFactory.getLogger(CreditCheckConsumer.class);

  private final ComponentClient componentClient;

  public CreditCheckConsumer(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public Effect onEvent(CreditStatusEvent event) {
    logger.info("Received credit status event for client {} status {}", event.clientId(), event.status());

    componentClient.forWorkflow(event.clientId())
        .method(ClientWorkflow::creditCheckStatus)
        .invoke(new ClientWorkflow.CreditCheckUpdate(event.status()));

    return effects().done();
  }
}