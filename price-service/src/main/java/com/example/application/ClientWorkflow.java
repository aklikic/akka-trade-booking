package com.example.application;

import akka.Done;
import akka.javasdk.NotificationPublisher;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.StepName;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import com.example.client.CreditCheckService;
import com.example.domain.ClientWorkflowState;
import com.example.domain.CreditStatus;
import com.example.domain.Quota;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static java.time.Duration.ofSeconds;

@Component(id = "client-workflow")
public class ClientWorkflow extends Workflow<ClientWorkflowState> {

    private static final Logger logger = LoggerFactory.getLogger(ClientWorkflow.class);

    public record PriceRateUpdate(String quotaId, String ccyPair, String tenor, double bid, double ask) {}

    public record CreditCheckUpdate(CreditStatus status) {}

    private final ComponentClient componentClient;
    private final CreditCheckService creditCheckService;

    public ClientWorkflow(
            ComponentClient componentClient,
            CreditCheckService creditCheckService) {
        this.componentClient = componentClient;
        this.creditCheckService = creditCheckService;
    }

    @Override
    public WorkflowSettings settings() {
        return WorkflowSettings.builder()
                .defaultStepTimeout(ofSeconds(5))
                .defaultStepRecovery(maxRetries(3).failoverTo(ClientWorkflow::failoverStep))
                .build();
    }

    // --- Command handlers ---

    public Effect<Done> subscribe(String ccyPair) {
        var state = currentState() == null
                ? ClientWorkflowState.initial(clientId())
                : currentState();

        if (state.isSubscribed(ccyPair) || state.isSubscribingPair(ccyPair)) {
            return effects().reply(Done.getInstance());
        }
        if (state.isBusy()) {
            return effects().error("Subscribe/unsubscribe already in progress for " + state.pendingPair().orElse("unknown"));
        }

        return effects()
                .updateState(state
                        .withSubscription(ccyPair)
                        .withPending(ClientWorkflowState.Status.SUBSCRIBING, ccyPair))
                .transitionTo(ClientWorkflow::subscribeToPriceRateStep)
                .withInput(ccyPair)
                .thenReply(Done.getInstance());
    }

    public Effect<Done> unsubscribe(String ccyPair) {
        if (currentState() == null || !currentState().isSubscribed(ccyPair) || currentState().isUnsubscribingPair(ccyPair)) {
            return effects().reply(Done.getInstance());
        }
        if (currentState().isBusy()) {
            return effects().error("Subscribe/unsubscribe already in progress for " + currentState().pendingPair().orElse("unknown"));
        }

        return effects()
                .updateState(currentState()
                        .withoutSubscription(ccyPair)
                        .withPending(ClientWorkflowState.Status.UNSUBSCRIBING, ccyPair))
                .transitionTo(ClientWorkflow::unsubscribeFromPriceRateStep)
                .withInput(ccyPair)
                .thenReply(Done.getInstance());
    }

    public Effect<Done> creditCheckStatus(CreditCheckUpdate update) {
        if (currentState() == null) {
            return effects().reply(Done.getInstance());
        }

        if (currentState().isBusy()) {
            return effects().error("Busy");
        }

        return effects()
                .updateState(currentState().withCreditStatus(update.status()))
                .pause()
                .thenReply(Done.getInstance());
    }

    public ReadOnlyEffect<ClientWorkflowState> getState() {
        if (currentState() == null) {
            return effects().reply(ClientWorkflowState.initial(clientId()));
        }
        return effects().reply(currentState());
    }

    // --- Steps ---

    @StepName("subscribe-price-rate")
    private StepEffect subscribeToPriceRateStep(String ccyPair) {

        logger.info("Subscribing to price rate client {} to {}", clientId(), ccyPair);
        componentClient
                .forEventSourcedEntity(ccyPair)
                .method(PriceEntity::subscribe)
                .invoke(clientId());

        return stepEffects()
                .thenTransitionTo(ClientWorkflow::subscribeToCreditCheck)
                .withInput(ccyPair);
    }

    @StepName("subscribe-credit-check")
    private StepEffect subscribeToCreditCheck(String ccyPair) {
        logger.info("Subscribing to credit check client {} to {}", clientId(), ccyPair);

        creditCheckService.subscribe(clientId());

        return stepEffects()
                .updateState(currentState().withIdle())
                .thenPause();
    }


    @StepName("unsubscribe-price-rate")
    private StepEffect unsubscribeFromPriceRateStep(String ccyPair) {
        logger.info("Unsubscribing from price rate client {} from {}", clientId(), ccyPair);

        componentClient
                .forEventSourcedEntity(ccyPair)
                .method(PriceEntity::unsubscribe)
                .invoke(clientId());

        return stepEffects()
                .thenTransitionTo(ClientWorkflow::unsubscribeFromCreditCheckStep)
                .withInput(ccyPair);
    }

    @StepName("unsubscribe-credit-check")
    private StepEffect unsubscribeFromCreditCheckStep(String ccyPair) {
        logger.info("Unsubscribing from credit check client {} from {}", clientId(), ccyPair);

        creditCheckService.unsubscribe(clientId());

        return stepEffects()
                .updateState(currentState().withIdle())
                .thenPause();
    }

    @StepName("failover")
    private StepEffect failoverStep() {
        logger.warn("Step failed for client {}, pausing workflow", clientId());
        return stepEffects()
                .updateState(currentState().withIdle())
                .thenPause();
    }

    private String clientId() {
        return commandContext().workflowId();
    }
}