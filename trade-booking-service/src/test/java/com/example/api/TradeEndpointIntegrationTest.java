package com.example.api;

import akka.javasdk.DependencyProvider;
import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.example.application.TradeBookingWorkflow;
import com.example.application.TradesByClientView;
import com.example.client.AutoHedgerServiceClient;
import com.example.client.AutoHedgerServiceClientStub;
import com.example.client.PricingServiceClient;
import com.example.client.PricingServiceClientStub;
import com.example.domain.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class TradeEndpointIntegrationTest extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withDependencyProvider(new DependencyProvider() {
      private final AutoHedgerServiceClient hedger = new AutoHedgerServiceClientStub();
      private final PricingServiceClient pricing = new PricingServiceClientStub();

      @SuppressWarnings("unchecked")
      @Override
      public <T> T getDependency(Class<T> clazz) {
        if (clazz == AutoHedgerServiceClient.class) {
          return (T) hedger;
        }
        if (clazz == PricingServiceClient.class) {
          return (T) pricing;
        }
        throw new RuntimeException("No such dependency: " + clazz);
      }
    });
  }

  @Test
  public void shouldAcceptQuoteViaEndpoint() throws Exception {
    var clientId = "ep-client-1";
    var priceRateId = "pr-test-1";
    var quoteId = "ep-test-1";
    var tradeId = TradeBookingWorkflow.tradeId(clientId, quoteId);

    // Start SSE listener before triggering the workflow
    var firstNotifications = CompletableFuture.supplyAsync(() ->
        testKit.getSelfSseRouteTester().receiveFirstN("/trades/" + tradeId + "/notifications", 1, Duration.ofSeconds(10)));

    // Trigger accept via endpoint with simplified request
    var acceptResponse = httpClient
        .POST("/trades/accept")
        .withRequestBody(new TradeEndpoint.AcceptRequest(quoteId, priceRateId, clientId, "BUY", 1_000_000))
        .invoke();

    assertThat(acceptResponse.httpResponse().status().intValue()).isEqualTo(200);

    var sse = firstNotifications.get(10, TimeUnit.SECONDS)
        .stream().map(evt -> JsonSupport.decodeJson(TradeNotification.class, evt.getData().getBytes())).toList();
    assertThat(sse).hasSize(1);
    assertThat(sse.getFirst().status()).isEqualTo(TradeStatus.CONFIRMED);
    assertThat(sse.getFirst().tradeId()).isEqualTo(tradeId);
    assertThat(sse.getFirst().quotaId()).isEqualTo(quoteId);
    assertThat(sse.getFirst().side()).isEqualTo("BUY");
  }

  @Test
  public void shouldGetTradeByTradeId() {
    var clientId = "ep-client-2";
    var priceRateId = "pr-test-2";
    var quoteId = "ep-test-2";
    var tradeId = TradeBookingWorkflow.tradeId(clientId, quoteId);
    var quota = new Quota(quoteId, priceRateId, clientId, new Instrument("GBPUSD", "SPOT"), 1.2700, 1.2705, CreditStatus.OK, System.currentTimeMillis());

    componentClient.forWorkflow(tradeId)
        .method(TradeBookingWorkflow::acceptQuote)
        .invoke(new TradeBookingWorkflow.AcceptQuoteCommand(quota, "SELL", 500_000));

    // Wait for workflow to finish
    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var state = componentClient.forWorkflow(tradeId)
          .method(TradeBookingWorkflow::getState)
          .invoke();
      assertThat(state.status()).isEqualTo(TradeStatus.CONFIRMED);
    });

    Awaitility.await()
        .ignoreExceptions()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> {
          var response = httpClient
              .GET("/trades/" + tradeId)
              .responseBodyAs(TradeEndpoint.TradeResponse.class)
              .invoke();

          assertThat(response.status().isSuccess()).isTrue();
          assertThat(response.body().tradeId()).isEqualTo(tradeId);
          assertThat(response.body().quotaId()).isEqualTo(quoteId);
          assertThat(response.body().status()).isEqualTo("CONFIRMED");
        });
  }

  @Test
  public void shouldStreamTradeUpdatesByClient() throws Exception {
    var clientId = "ep-client-3";
    var priceRateId = "pr-test-stream";
    var quoteId = "ep-test-stream";
    var tradeId = TradeBookingWorkflow.tradeId(clientId, quoteId);

    // Start SSE listener on the by-client stream
    var firstUpdates = CompletableFuture.supplyAsync(() ->
        testKit.getSelfSseRouteTester().receiveFirstN("/trades/by-client/" + clientId + "/updates", 1, Duration.ofSeconds(10)));

    // Trigger a trade
    var quota = new Quota(quoteId, priceRateId, clientId, new Instrument("EURCHF", "SPOT"), 0.9450, 0.9455, CreditStatus.OK, System.currentTimeMillis());
    componentClient.forWorkflow(tradeId)
        .method(TradeBookingWorkflow::acceptQuote)
        .invoke(new TradeBookingWorkflow.AcceptQuoteCommand(quota, "BUY", 750_000));

    var sse = firstUpdates.get(10, TimeUnit.SECONDS)
        .stream().map(evt -> JsonSupport.decodeJson(TradesByClientView.TradeEntry.class, evt.getData().getBytes())).toList();
    assertThat(sse).isNotEmpty();
    assertThat(sse.stream().anyMatch(t -> t.tradeId().equals(tradeId))).isTrue();
  }

  @Test
  public void shouldRejectQuoteWithFailedCredit() throws Exception {
    var clientId = "ep-client-4";
    var priceRateId = "pr-test-4";
    var quoteId = "ep-test-3";
    var tradeId = TradeBookingWorkflow.tradeId(clientId, quoteId);
    var quota = new Quota(quoteId, priceRateId, clientId, new Instrument("USDJPY", "SPOT"), 150.25, 150.30, CreditStatus.FAIL, System.currentTimeMillis());

    var firstNotifications = CompletableFuture.supplyAsync(() ->
        testKit.getSelfSseRouteTester().receiveFirstN("/trades/" + tradeId + "/notifications", 1, Duration.ofSeconds(10)));

    componentClient.forWorkflow(tradeId)
        .method(TradeBookingWorkflow::acceptQuote)
        .invoke(new TradeBookingWorkflow.AcceptQuoteCommand(quota, "BUY", 250_000));

    var sse = firstNotifications.get(10, TimeUnit.SECONDS)
        .stream().map(evt -> JsonSupport.decodeJson(TradeNotification.class, evt.getData().getBytes())).toList();
    assertThat(sse).hasSize(1);
    assertThat(sse.getFirst().status()).isEqualTo(TradeStatus.REJECTED);
    assertThat(sse.getFirst().quotaId()).isEqualTo(quoteId);
    assertThat(sse.getFirst().preTradeResult().name()).isEqualTo("CREDIT_CHECK_FAILED");
  }
}