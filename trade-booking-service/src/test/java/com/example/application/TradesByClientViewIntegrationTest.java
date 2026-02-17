package com.example.application;

import akka.javasdk.DependencyProvider;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.example.client.AutoHedgerServiceClient;
import com.example.client.AutoHedgerServiceClientStub;
import com.example.domain.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class TradesByClientViewIntegrationTest extends TestKitSupport {

  private static final String CLIENT_ID = "view-client-1";

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

  private static Quota createQuota(String quoteId, String priceRateId, String ccyPair, CreditStatus creditStatus) {
    return new Quota(quoteId, priceRateId, CLIENT_ID, new Instrument(ccyPair, "SPOT"), 1.1050, 1.1055, creditStatus, System.currentTimeMillis());
  }

  private static String tradeId(String quoteId) {
    return TradeBookingWorkflow.tradeId(CLIENT_ID, quoteId);
  }

  @Test
  public void shouldProjectConfirmedTrade() {
    var quoteId = "view-q-1";
    var tradeId = tradeId(quoteId);
    var quota = createQuota(quoteId, "pr-1", "EURUSD", CreditStatus.OK);

    componentClient.forWorkflow(tradeId)
        .method(TradeBookingWorkflow::acceptQuote)
        .invoke(new TradeBookingWorkflow.AcceptQuoteCommand(quota, "BUY", 1_000_000));

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var state = componentClient.forWorkflow(tradeId)
          .method(TradeBookingWorkflow::getState)
          .invoke();
      assertThat(state.status()).isEqualTo(TradeStatus.CONFIRMED);
    });

    Awaitility.await().atMost(10, TimeUnit.SECONDS).ignoreExceptions().untilAsserted(() -> {
      var result = componentClient.forView()
          .method(TradesByClientView::getByClientId)
          .invoke(CLIENT_ID);
      assertThat(result.entries()).isNotEmpty();
      var entry = result.entries().stream()
          .filter(e -> e.tradeId().equals(tradeId))
          .findFirst();
      assertThat(entry).isPresent();
      assertThat(entry.get().clientId()).isEqualTo(CLIENT_ID);
      assertThat(entry.get().ccyPair()).isEqualTo("EURUSD");
      assertThat(entry.get().side()).isEqualTo("BUY");
      assertThat(entry.get().quantity()).isEqualTo(1_000_000);
      assertThat(entry.get().status()).isEqualTo("CONFIRMED");
    });
  }

  @Test
  public void shouldProjectRejectedTrade() {
    var quoteId = "view-q-2";
    var tradeId = tradeId(quoteId);
    var quota = createQuota(quoteId, "pr-2", "GBPUSD", CreditStatus.FAIL);

    componentClient.forWorkflow(tradeId)
        .method(TradeBookingWorkflow::acceptQuote)
        .invoke(new TradeBookingWorkflow.AcceptQuoteCommand(quota, "SELL", 500_000));

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var state = componentClient.forWorkflow(tradeId)
          .method(TradeBookingWorkflow::getState)
          .invoke();
      assertThat(state.status()).isEqualTo(TradeStatus.REJECTED);
    });

    Awaitility.await().atMost(10, TimeUnit.SECONDS).ignoreExceptions().untilAsserted(() -> {
      var result = componentClient.forView()
          .method(TradesByClientView::getByClientId)
          .invoke(CLIENT_ID);
      var entry = result.entries().stream()
          .filter(e -> e.tradeId().equals(tradeId))
          .findFirst();
      assertThat(entry).isPresent();
      assertThat(entry.get().status()).isEqualTo("REJECTED");
      assertThat(entry.get().preTradeResult()).isEqualTo("CREDIT_CHECK_FAILED");
    });
  }
}