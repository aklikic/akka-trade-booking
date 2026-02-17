package com.example.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public record ClientWorkflowState(
    String clientId,
    Set<String> subscriptions,
    CreditStatus creditStatus,
//    Optional<Quota> latestQuota,
    Status status,
    Optional<String> pendingPair) {

  public enum Status {
    IDLE, SUBSCRIBING, UNSUBSCRIBING
  }

  public static ClientWorkflowState initial(String clientId) {
    return new ClientWorkflowState(clientId, Set.of(), CreditStatus.UNKNOWN,  Status.IDLE, Optional.empty());
  }

  public boolean isBusy() {
    return status != Status.IDLE;
  }

  public boolean isSubscribingPair(String ccyPair) {
    return status == Status.SUBSCRIBING && pendingPair.map(ccyPair::equals).orElse(false);
  }

  public boolean isUnsubscribingPair(String ccyPair) {
    return status == Status.UNSUBSCRIBING && pendingPair.map(ccyPair::equals).orElse(false);
  }

  public ClientWorkflowState withPending(Status newStatus, String ccyPair) {
    return new ClientWorkflowState(clientId, subscriptions, creditStatus,  newStatus, Optional.of(ccyPair));
  }

  public ClientWorkflowState withIdle() {
    return new ClientWorkflowState(clientId, subscriptions, creditStatus, Status.IDLE, Optional.empty());
  }

  public ClientWorkflowState withSubscription(String ccyPair) {
    var updated = new HashSet<>(subscriptions);
    updated.add(ccyPair);
    return new ClientWorkflowState(clientId, Set.copyOf(updated), creditStatus, status, pendingPair);
  }

  public ClientWorkflowState withoutSubscription(String ccyPair) {
    var updated = new HashSet<>(subscriptions);
    updated.remove(ccyPair);
    return new ClientWorkflowState(clientId, Set.copyOf(updated), creditStatus,  status, pendingPair);
  }

  public ClientWorkflowState withCreditStatus(CreditStatus newCreditStatus) {
    return new ClientWorkflowState(clientId, subscriptions, newCreditStatus,  status, pendingPair);
  }

//  public ClientWorkflowState withQuota(Quota quota) {
//    return new ClientWorkflowState(clientId, subscriptions, creditStatus, Optional.of(quota), status, pendingPair);
//  }

  public boolean isSubscribed(String ccyPair) {
    return subscriptions.contains(ccyPair);
  }

//  public boolean isDuplicateRate(String ccyPair, double bid, double ask) {
//    return latestQuota
//        .map(q -> q.ccyPair().equals(ccyPair) && q.bid() == bid && q.ask() == ask)
//        .orElse(false);
//  }

  public Quota createQuota(String quotaId, String priceRateId, String ccyPair, String tenor, double bid, double ask) {
    return new Quota(quotaId,priceRateId, clientId, ccyPair, tenor, bid, ask, creditStatus, System.currentTimeMillis());
  }
}