package com.example.application;

import akka.Done;
import akka.javasdk.testkit.EventSourcedTestKit;
import com.example.domain.CreditStatus;
import com.example.domain.PriceEvent;
import com.example.domain.PriceRateClientQuota;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class PriceEntityTest {

  @Test
  public void shouldSubscribeClient() {
    var testKit = EventSourcedTestKit.of(PriceEntity::new);
    var result = testKit.method(PriceEntity::subscribe).invoke("client-1");

    assertThat(result.isReply()).isTrue();
    assertThat(result.getReply()).isEqualTo(Done.getInstance());

    var subscribed = result.getNextEventOfType(PriceEvent.Subscribed.class);
    assertThat(subscribed.clientId()).isEqualTo("client-1");

    var firstSubscribed = result.getNextEventOfType(PriceEvent.FirstSubscribed.class);
    assertThat(firstSubscribed.ccyPair()).isNotEmpty();

    assertThat(testKit.getState().subscriptions()).containsExactly("client-1");
  }

  @Test
  public void shouldNotEmitFirstSubscribedOnSecondClient() {
    var testKit = EventSourcedTestKit.of(PriceEntity::new);
    testKit.method(PriceEntity::subscribe).invoke("client-1");

    var result = testKit.method(PriceEntity::subscribe).invoke("client-2");

    assertThat(result.getAllEvents()).hasSize(1);
    assertThat(result.getNextEventOfType(PriceEvent.Subscribed.class).clientId()).isEqualTo("client-2");
  }

  @Test
  public void shouldIgnoreDuplicateSubscribe() {
    var testKit = EventSourcedTestKit.of(PriceEntity::new);
    testKit.method(PriceEntity::subscribe).invoke("client-1");

    var result = testKit.method(PriceEntity::subscribe).invoke("client-1");

    assertThat(result.isReply()).isTrue();
    assertThat(result.getAllEvents()).isEmpty();
  }

  @Test
  public void shouldUnsubscribeLastClientAndEmitAllUnsubscribed() {
    var testKit = EventSourcedTestKit.of(PriceEntity::new);
    testKit.method(PriceEntity::subscribe).invoke("client-1");

    var result = testKit.method(PriceEntity::unsubscribe).invoke("client-1");

    assertThat(result.isReply()).isTrue();
    assertThat(result.getAllEvents()).hasSize(2);
    var unsubscribed = result.getNextEventOfType(PriceEvent.Unsubscribed.class);
    assertThat(unsubscribed.clientId()).isEqualTo("client-1");
    result.getNextEventOfType(PriceEvent.AllUnsubscribed.class);
    assertThat(testKit.getState().subscriptions()).isEmpty();
  }

  @Test
  public void shouldNotEmitAllUnsubscribedWhenOthersRemain() {
    var testKit = EventSourcedTestKit.of(PriceEntity::new);
    testKit.method(PriceEntity::subscribe).invoke("client-1");
    testKit.method(PriceEntity::subscribe).invoke("client-2");

    var result = testKit.method(PriceEntity::unsubscribe).invoke("client-1");

    assertThat(result.getAllEvents()).hasSize(1);
    assertThat(result.getNextEventOfType(PriceEvent.Unsubscribed.class).clientId()).isEqualTo("client-1");
    assertThat(testKit.getState().subscriptions()).containsExactly("client-2");
  }

  @Test
  public void shouldRejectUnsubscribeForUnknownClient() {
    var testKit = EventSourcedTestKit.of(PriceEntity::new);

    var result = testKit.method(PriceEntity::unsubscribe).invoke("client-1");

    assertThat(result.isError()).isTrue();
  }

  @Test
  public void shouldReturnSubscriptions() {
    var testKit = EventSourcedTestKit.of(PriceEntity::new);
    testKit.method(PriceEntity::subscribe).invoke("client-1");
    testKit.method(PriceEntity::subscribe).invoke("client-2");

    var result = testKit.method(PriceEntity::getSubscriptions).invoke();

    assertThat(result.isReply()).isTrue();
    assertThat(result.getReply()).containsExactlyInAnyOrder("client-1", "client-2");
  }

  @Test
  public void shouldTrackMultipleSubscriptionsAndRemovals() {
    var testKit = EventSourcedTestKit.of(PriceEntity::new);
    testKit.method(PriceEntity::subscribe).invoke("client-1");
    testKit.method(PriceEntity::subscribe).invoke("client-2");
    testKit.method(PriceEntity::unsubscribe).invoke("client-1");

    assertThat(testKit.getState().subscriptions()).containsExactly("client-2");
  }

  @Test
  public void shouldAddPriceRate() {
    var testKit = EventSourcedTestKit.of(PriceEntity::new);
    testKit.method(PriceEntity::subscribe).invoke("client-1");

    var quotas = List.of(new PriceRateClientQuota("q1", "client-1", CreditStatus.OK));
    var update = new PriceEntity.PriceRateUpdate("SPOT", 1.1050, 1.1055, 1, 1700000000000L, quotas);
    var result = testKit.method(PriceEntity::priceRateUpdate).invoke(update);

    assertThat(result.isReply()).isTrue();
    var event = result.getNextEventOfType(PriceEvent.PriceRateAdded.class);
    assertThat(event.priceRate().tenor()).isEqualTo("SPOT");
    assertThat(event.priceRate().bid()).isEqualTo(1.1050);
    assertThat(event.priceRate().ask()).isEqualTo(1.1055);
    assertThat(event.quotas()).hasSize(1);
    assertThat(testKit.getState().lastPriceRate()).isPresent();
  }

  @Test
  public void shouldIgnoreRateUpdateWithNoSubscriptions() {
    var testKit = EventSourcedTestKit.of(PriceEntity::new);

    var update = new PriceEntity.PriceRateUpdate("SPOT", 1.1050, 1.1055, 1, 1700000000000L, List.of());
    var result = testKit.method(PriceEntity::priceRateUpdate).invoke(update);

    assertThat(result.isReply()).isTrue();
    assertThat(result.getAllEvents()).isEmpty();
  }

  @Test
  public void shouldIgnoreDuplicateRate() {
    var testKit = EventSourcedTestKit.of(PriceEntity::new);
    testKit.method(PriceEntity::subscribe).invoke("client-1");

    var quotas = List.of(new PriceRateClientQuota("q1", "client-1", CreditStatus.OK));
    var update1 = new PriceEntity.PriceRateUpdate("SPOT", 1.1050, 1.1055, 1, 1700000000000L, quotas);
    testKit.method(PriceEntity::priceRateUpdate).invoke(update1);

    var update2 = new PriceEntity.PriceRateUpdate("SPOT", 1.1050, 1.1055, 2, 1700000001000L, quotas);
    var result = testKit.method(PriceEntity::priceRateUpdate).invoke(update2);

    assertThat(result.isReply()).isTrue();
    assertThat(result.getAllEvents()).isEmpty();
  }

  @Test
  public void shouldAcceptRateWithDifferentBidAsk() {
    var testKit = EventSourcedTestKit.of(PriceEntity::new);
    testKit.method(PriceEntity::subscribe).invoke("client-1");

    var quotas = List.of(new PriceRateClientQuota("q1", "client-1", CreditStatus.OK));
    var update1 = new PriceEntity.PriceRateUpdate("SPOT", 1.1050, 1.1055, 1, 1700000000000L, quotas);
    testKit.method(PriceEntity::priceRateUpdate).invoke(update1);

    var update2 = new PriceEntity.PriceRateUpdate("SPOT", 1.1060, 1.1065, 2, 1700000001000L, quotas);
    var result = testKit.method(PriceEntity::priceRateUpdate).invoke(update2);

    assertThat(result.getAllEvents()).hasSize(1);
    var event = result.getNextEventOfType(PriceEvent.PriceRateAdded.class);
    assertThat(event.priceRate().bid()).isEqualTo(1.1060);
  }

  @Test
  public void shouldUsePriceRateIdWhenProvided() {
    var testKit = EventSourcedTestKit.of(PriceEntity::new);
    testKit.method(PriceEntity::subscribe).invoke("client-1");

    var quotas = List.of(new PriceRateClientQuota("q1", "client-1", CreditStatus.OK));
    var update = new PriceEntity.PriceRateUpdate("SPOT", 1.1050, 1.1055, 1, 1700000000000L, quotas, java.util.Optional.of("my-rate-id"));
    var result = testKit.method(PriceEntity::priceRateUpdate).invoke(update);

    var event = result.getNextEventOfType(PriceEvent.PriceRateAdded.class);
    assertThat(event.priceRate().priceRateId()).isEqualTo("my-rate-id");
  }
}