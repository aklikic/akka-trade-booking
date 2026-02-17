package com.example.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class ClientWorkflowStateTest {

  @Test
  public void shouldCreateInitialState() {
    var state = ClientWorkflowState.initial("client-1");

    assertThat(state.clientId()).isEqualTo("client-1");
    assertThat(state.subscriptions()).isEmpty();
    assertThat(state.creditStatus()).isEqualTo(CreditStatus.UNKNOWN);
    assertThat(state.status()).isEqualTo(ClientWorkflowState.Status.IDLE);
    assertThat(state.pendingPair()).isEmpty();
    assertThat(state.isBusy()).isFalse();
  }

  @Test
  public void shouldAddSubscription() {
    var state = ClientWorkflowState.initial("client-1")
        .withSubscription("EURUSD");

    assertThat(state.subscriptions()).containsExactly("EURUSD");
    assertThat(state.isSubscribed("EURUSD")).isTrue();
    assertThat(state.isSubscribed("GBPUSD")).isFalse();
  }

  @Test
  public void shouldRemoveSubscription() {
    var state = ClientWorkflowState.initial("client-1")
        .withSubscription("EURUSD")
        .withSubscription("GBPUSD")
        .withoutSubscription("EURUSD");

    assertThat(state.subscriptions()).containsExactly("GBPUSD");
    assertThat(state.isSubscribed("EURUSD")).isFalse();
  }

  @Test
  public void shouldTrackPendingSubscribing() {
    var state = ClientWorkflowState.initial("client-1")
        .withPending(ClientWorkflowState.Status.SUBSCRIBING, "EURUSD");

    assertThat(state.isBusy()).isTrue();
    assertThat(state.isSubscribingPair("EURUSD")).isTrue();
    assertThat(state.isSubscribingPair("GBPUSD")).isFalse();
    assertThat(state.isUnsubscribingPair("EURUSD")).isFalse();
  }

  @Test
  public void shouldTrackPendingUnsubscribing() {
    var state = ClientWorkflowState.initial("client-1")
        .withPending(ClientWorkflowState.Status.UNSUBSCRIBING, "EURUSD");

    assertThat(state.isBusy()).isTrue();
    assertThat(state.isUnsubscribingPair("EURUSD")).isTrue();
    assertThat(state.isUnsubscribingPair("GBPUSD")).isFalse();
    assertThat(state.isSubscribingPair("EURUSD")).isFalse();
  }

  @Test
  public void shouldTransitionToIdle() {
    var state = ClientWorkflowState.initial("client-1")
        .withPending(ClientWorkflowState.Status.SUBSCRIBING, "EURUSD")
        .withIdle();

    assertThat(state.isBusy()).isFalse();
    assertThat(state.status()).isEqualTo(ClientWorkflowState.Status.IDLE);
    assertThat(state.pendingPair()).isEmpty();
  }

  @Test
  public void shouldUpdateCreditStatus() {
    var state = ClientWorkflowState.initial("client-1")
        .withCreditStatus(CreditStatus.OK);

    assertThat(state.creditStatus()).isEqualTo(CreditStatus.OK);

    state = state.withCreditStatus(CreditStatus.FAIL);
    assertThat(state.creditStatus()).isEqualTo(CreditStatus.FAIL);
  }

  @Test
  public void shouldCreateQuota() {
    var state = ClientWorkflowState.initial("client-1")
        .withCreditStatus(CreditStatus.OK);

    var quota = state.createQuota("q1", "rate-1", "EURUSD", "SPOT", 1.1050, 1.1055);

    assertThat(quota.quotaId()).isEqualTo("q1");
    assertThat(quota.priceRateId()).isEqualTo("rate-1");
    assertThat(quota.clientId()).isEqualTo("client-1");
    assertThat(quota.ccyPair()).isEqualTo("EURUSD");
    assertThat(quota.tenor()).isEqualTo("SPOT");
    assertThat(quota.bid()).isEqualTo(1.1050);
    assertThat(quota.ask()).isEqualTo(1.1055);
    assertThat(quota.creditStatus()).isEqualTo(CreditStatus.OK);
    assertThat(quota.timestamp()).isGreaterThan(0);
  }

  @Test
  public void shouldBeImmutableOnSubscriptionAdd() {
    var original = ClientWorkflowState.initial("client-1");
    var updated = original.withSubscription("EURUSD");

    assertThat(original.subscriptions()).isEmpty();
    assertThat(updated.subscriptions()).containsExactly("EURUSD");
  }
}