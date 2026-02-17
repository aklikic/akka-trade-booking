package com.example.application;

import akka.javasdk.DependencyProvider;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.example.client.AutoHedgerServiceClient;
import com.example.client.AutoHedgerServiceClientStub;
import com.example.domain.*;
import com.example.domain.TradeBookingState.PreTradeResult;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class TradeBookingWorkflowIntegrationTest extends TestKitSupport {

  private static final String CLIENT_ID = "client-1";

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withDependencyProvider(new DependencyProvider() {
      private final AutoHedgerServiceClient hedger = new AutoHedgerServiceClientStub();

      @SuppressWarnings("unchecked")
      @Override
      public <T> T getDependency(Class<T> clazz) {
        if (clazz == AutoHedgerServiceClient.class) {
          return (T) hedger;
        }
        throw new RuntimeException("No such dependency: " + clazz);
      }
    });
  }

  private static Quota createQuota(String quoteId, String priceRateId, String ccyPair, double bid, double ask, CreditStatus creditStatus) {
    return new Quota(quoteId, priceRateId, CLIENT_ID, new Instrument(ccyPair, "SPOT"), bid, ask, creditStatus, System.currentTimeMillis());
  }

  private static String tradeId(String quoteId) {
    return TradeBookingWorkflow.tradeId(CLIENT_ID, quoteId);
  }

  @Test
  public void shouldConfirmTradeOnAcceptWithOkCredit() {
    var priceRateId = "pr-test-1";
    var quoteId = "wf-test-1";
    var quota = createQuota(quoteId, priceRateId,"EURUSD", 1.1050, 1.1055, CreditStatus.OK);

    componentClient.forWorkflow(tradeId(quoteId))
        .method(TradeBookingWorkflow::acceptQuote)
        .invoke(new TradeBookingWorkflow.AcceptQuoteCommand(quota, "BUY", 1_000_000));

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var state = componentClient.forWorkflow(tradeId(quoteId))
          .method(TradeBookingWorkflow::getState)
          .invoke();
      assertThat(state.status()).isEqualTo(TradeStatus.CONFIRMED);
      assertThat(state.tradeId()).isEqualTo(tradeId(quoteId));
      assertThat(state.preTradeResult()).isEqualTo(PreTradeResult.OK);
      assertThat(state.quota()).isNotNull();
      assertThat(state.side()).isEqualTo("BUY");
      assertThat(state.quantity()).isEqualTo(1_000_000);
    });
  }

  @Test
  public void shouldRejectTradeOnFailedCredit() {
    var priceRateId = "pr-test-1";
    var quoteId = "wf-test-2";
    var quota = createQuota(quoteId,priceRateId, "GBPUSD", 1.2700, 1.2705, CreditStatus.FAIL);

    componentClient.forWorkflow(tradeId(quoteId))
        .method(TradeBookingWorkflow::acceptQuote)
        .invoke(new TradeBookingWorkflow.AcceptQuoteCommand(quota, "SELL", 500_000));

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var state = componentClient.forWorkflow(tradeId(quoteId))
          .method(TradeBookingWorkflow::getState)
          .invoke();
      assertThat(state.status()).isEqualTo(TradeStatus.REJECTED);
      assertThat(state.preTradeResult()).isEqualTo(PreTradeResult.CREDIT_CHECK_FAILED);
    });
  }

  @Test
  public void shouldRejectTradeOnUnknownCredit() {
    var priceRateId = "pr-test-1";
    var quoteId = "wf-test-3";
    var quota = createQuota(quoteId, priceRateId, "USDJPY", 150.25, 150.30, CreditStatus.UNKNOWN);

    componentClient.forWorkflow(tradeId(quoteId))
        .method(TradeBookingWorkflow::acceptQuote)
        .invoke(new TradeBookingWorkflow.AcceptQuoteCommand(quota, "BUY", 250_000));

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var state = componentClient.forWorkflow(tradeId(quoteId))
          .method(TradeBookingWorkflow::getState)
          .invoke();
      assertThat(state.status()).isEqualTo(TradeStatus.REJECTED);
      assertThat(state.preTradeResult()).isEqualTo(PreTradeResult.CREDIT_STATUS_UNKNOWN);
    });
  }

  @Test
  public void shouldBeIdempotentOnDuplicateAcceptQuote() {
    var priceRateId = "pr-test-1";
    var quoteId = "wf-test-4";
    var quota = createQuota(quoteId, priceRateId,"EURJPY", 162.50, 162.55, CreditStatus.OK);

    componentClient.forWorkflow(tradeId(quoteId))
        .method(TradeBookingWorkflow::acceptQuote)
        .invoke(new TradeBookingWorkflow.AcceptQuoteCommand(quota, "BUY", 100_000));

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var state = componentClient.forWorkflow(tradeId(quoteId))
          .method(TradeBookingWorkflow::getState)
          .invoke();
      assertThat(state.status()).isEqualTo(TradeStatus.CONFIRMED);
    });

    // Duplicate call should return tradeId without error
    componentClient.forWorkflow(tradeId(quoteId))
        .method(TradeBookingWorkflow::acceptQuote)
        .invoke(new TradeBookingWorkflow.AcceptQuoteCommand(quota, "BUY", 100_000));

    var state = componentClient.forWorkflow(tradeId(quoteId))
        .method(TradeBookingWorkflow::getState)
        .invoke();
    assertThat(state.status()).isEqualTo(TradeStatus.CONFIRMED);
  }
}
