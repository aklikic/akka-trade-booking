package com.example.application;

import akka.javasdk.NotificationPublisher;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.StepName;
import akka.javasdk.workflow.Workflow;
import com.example.client.AutoHedgerServiceClient;
import com.example.client.HedgeRequest;
import com.example.domain.Quota;
import com.example.domain.TradeBookingState;
import com.example.domain.TradeNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.time.Duration.ofSeconds;

@Component(id = "trade-booking-workflow")
public class TradeBookingWorkflow extends Workflow<TradeBookingState> {

  private static final Logger logger = LoggerFactory.getLogger(TradeBookingWorkflow.class);

  public record AcceptQuoteCommand(Quota quota, String side, double quantity) {}

  public static String tradeId(String clientId, String quotaId) {
    return clientId + "_" + quotaId;
  }

  private final AutoHedgerServiceClient autoHedgerServiceClient;
  private final NotificationPublisher<TradeNotification> notificationPublisher;

  public TradeBookingWorkflow(
      AutoHedgerServiceClient autoHedgerServiceClient,
      NotificationPublisher<TradeNotification> notificationPublisher) {
    this.autoHedgerServiceClient = autoHedgerServiceClient;
    this.notificationPublisher = notificationPublisher;
  }

  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder()
        .defaultStepTimeout(ofSeconds(10))
        .defaultStepRecovery(maxRetries(2).failoverTo(TradeBookingWorkflow::failoverStep))
        .build();
  }

  // --- Command handlers ---

  public Effect<String> acceptQuote(AcceptQuoteCommand command) {
    logger.info("Accepting quote {}", commandContext().workflowId());
    if (currentState() != null) {
      return effects().reply(currentState().tradeId());
    }
    var tradeId = commandContext().workflowId();
    return effects()
        .updateState(TradeBookingState.initial(tradeId, command.quota(), command.side(), command.quantity()))
        .transitionTo(TradeBookingWorkflow::preTradeCheckStep)
        .thenReply(tradeId);
  }

  public ReadOnlyEffect<TradeBookingState> getState() {
    if (currentState() == null) {
      return effects().error("Workflow not started");
    }
    return effects().reply(currentState());
  }

  public NotificationPublisher.NotificationStream<TradeNotification> updates() {
    return notificationPublisher.stream();
  }

  // --- Steps ---

  @StepName("pre-trade-check")
  private StepEffect preTradeCheckStep() {
    var state = currentState();
    var quota = state.quota();
    logger.info("Pre-trade check for trade {}", state.tradeId());

    var result = TradeBookingState.validateCredit(quota.creditStatus());
    var newState = state.withPreTradeCheck(result);

    if (newState.isRejected()) {
      logger.info("Pre-trade check rejected for trade {}: {}", state.tradeId(), result);
      notificationPublisher.publish(TradeNotification.rejected(state.tradeId(), quota.quotaId(), result));
      return stepEffects()
          .updateState(newState)
          .thenEnd();
    }

    return stepEffects()
        .updateState(newState.withHedging())
        .thenTransitionTo(TradeBookingWorkflow::tradeHedgeStep);
  }

  @StepName("trade-hedge")
  private StepEffect tradeHedgeStep() {
    var state = currentState();
    logger.info("Submitting hedge for trade {}", state.tradeId());

    autoHedgerServiceClient.submit(new HedgeRequest(
        state.tradeId(),
        state.quota().instrument(),
        state.side(),
        state.quantity()));

    notificationPublisher.publish(TradeNotification.confirmed(
        state.tradeId(), state.quota().quotaId(), state.side(), state.quantity()));

    return stepEffects()
        .updateState(state.withConfirmed())
        .thenEnd();
  }

  @StepName("failover")
  private StepEffect failoverStep() {
    var state = currentState();
    logger.warn("Workflow step failed for trade {}, marking as rejected", state.tradeId());
    notificationPublisher.publish(TradeNotification.rejected(state.tradeId(), state.quota().quotaId(), state.preTradeResult()));

    return stepEffects()
        .updateState(state.withRejected(state.preTradeResult()))
        .thenEnd();
  }
}
