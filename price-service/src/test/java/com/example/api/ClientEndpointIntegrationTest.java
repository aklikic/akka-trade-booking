package com.example.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.EventingTestKit;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.example.application.ClientView;
import com.example.application.ClientWorkflow;
import com.example.application.PriceEntity;
import com.example.application.QuotaEntity;
import com.example.client.Instrument;
import com.example.domain.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class ClientEndpointIntegrationTest extends TestKitSupport {

  private EventingTestKit.IncomingMessages fxRateTopic;
  private EventingTestKit.IncomingMessages creditCheckTopic;

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withTopicIncomingMessages("fx-rate-events")
        .withTopicIncomingMessages("credit-check-events");
  }

  @BeforeAll
  public void beforeAll() {
    super.beforeAll();
    fxRateTopic = testKit.getTopicIncomingMessages("fx-rate-events");
    creditCheckTopic = testKit.getTopicIncomingMessages("credit-check-events");
  }

  @Test
  public void shouldSubscribe() {
    var clientId = "endpoint-client-1";
    var ccyPair = "EURUSD";

    var subscribeResponse = httpClient
        .POST("/clients/" + clientId + "/subscribe/" + ccyPair)
        .invoke();

    assertThat(subscribeResponse.httpResponse().status().intValue()).isEqualTo(200);

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var state = componentClient.forWorkflow(clientId)
          .method(ClientWorkflow::getState)
          .invoke();
      assertThat(state.subscriptions()).contains(ccyPair);
      assertThat(state.status()).isEqualTo(ClientWorkflowState.Status.IDLE);
    });
  }

  @Test
  public void shouldUnsubscribe() {
    var clientId = "endpoint-client-2";
    var ccyPair = "GBPUSD";

    httpClient
        .POST("/clients/" + clientId + "/subscribe/" + ccyPair)
        .invoke();

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var state = componentClient.forWorkflow(clientId)
          .method(ClientWorkflow::getState)
          .invoke();
      assertThat(state.status()).isEqualTo(ClientWorkflowState.Status.IDLE);
    });

    var unsubscribeResponse = httpClient
        .POST("/clients/" + clientId + "/unsubscribe/" + ccyPair)
        .invoke();

    assertThat(unsubscribeResponse.httpResponse().status().intValue()).isEqualTo(200);

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var state = componentClient.forWorkflow(clientId)
          .method(ClientWorkflow::getState)
          .invoke();
      assertThat(state.subscriptions()).doesNotContain(ccyPair);
      assertThat(state.status()).isEqualTo(ClientWorkflowState.Status.IDLE);
    });
  }

  @Test
  public void shouldSimulateRateUpdate() {
    var clientId = "endpoint-client-3";
    var ccyPair = "USDJPY";

    httpClient
        .POST("/clients/" + clientId + "/subscribe/" + ccyPair)
        .invoke();

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var state = componentClient.forWorkflow(clientId)
          .method(ClientWorkflow::getState)
          .invoke();
      assertThat(state.status()).isEqualTo(ClientWorkflowState.Status.IDLE);
    });

    // Wait for ClientView to be populated (eventually consistent)
    awaitClientInView(clientId);

    var response = httpClient
        .POST("/clients/simulate/rate-update")
        .withRequestBody(new ClientEndpoint.RateUpdate(ccyPair, "SPOT", 150.25, 150.30, 1, System.currentTimeMillis()))
        .invoke();

    assertThat(response.httpResponse().status().intValue()).isEqualTo(200);

    // Verify PriceEntity stored the rate and consumer created quotas in PriceRateQuotasEntity
    Awaitility.await().ignoreExceptions().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var lastRate = componentClient.forEventSourcedEntity(ccyPair)
          .method(PriceEntity::getLastPriceRate)
          .invoke();
      assertThat(lastRate).isPresent();
      assertThat(lastRate.get().bid()).isEqualTo(150.25);

      var quota = componentClient.forKeyValueEntity(lastRate.get().priceRateId())
          .method(QuotaEntity::get)
          .invoke(clientId);
      assertThat(quota).isPresent();
      assertThat(quota.get().clientId()).isEqualTo(clientId);
      assertThat(quota.get().ccyPair()).isEqualTo(ccyPair);
      assertThat(quota.get().bid()).isEqualTo(150.25);
    });
  }

  @Test
  public void shouldStreamQuotaViaSseOnRateUpdate() throws Exception {
    var clientId = "endpoint-client-3b";
    var ccyPair = "CHFJPY";

    httpClient
        .POST("/clients/" + clientId + "/subscribe/" + ccyPair)
        .invoke();

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var state = componentClient.forWorkflow(clientId)
          .method(ClientWorkflow::getState)
          .invoke();
      assertThat(state.status()).isEqualTo(ClientWorkflowState.Status.IDLE);
    });

    // Wait for ClientView to be populated (eventually consistent)
    awaitClientInView(clientId);

    var firstNotifications = CompletableFuture.supplyAsync(() ->
            testKit.getSelfSseRouteTester().receiveFirstN("/clients/" + clientId + "/quotas", 1, Duration.ofSeconds(20)));

    httpClient
        .POST("/clients/simulate/rate-update")
        .withRequestBody(new ClientEndpoint.RateUpdate(ccyPair, "SPOT", 150.25, 150.30, 1, System.currentTimeMillis()))
        .invoke();

    var sse = firstNotifications.get(20, TimeUnit.SECONDS)
        .stream().map(evt -> JsonSupport.decodeJson(Quota.class, evt.getData().getBytes())).toList();
    assertThat(sse).hasSize(1);
    assertThat(sse.get(0).clientId()).isEqualTo(clientId);
    assertThat(sse.get(0).ccyPair()).isEqualTo(ccyPair);
    assertThat(sse.get(0).bid()).isEqualTo(150.25);
  }

  @Test
  public void shouldProcessFxRateFromTopic() {
    var clientId = "endpoint-client-5";
    var ccyPair = "AUDUSD";

    httpClient
        .POST("/clients/" + clientId + "/subscribe/" + ccyPair)
        .invoke();

    // Verify PriceEntity received the rate
    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var subs = componentClient.forEventSourcedEntity(ccyPair)
              .method(PriceEntity::getSubscriptions)
              .invoke();
      assertThat(subs.isEmpty()).isFalse();
      assertThat(subs.getFirst()).isEqualTo(clientId);
    });

    awaitClientInView(clientId);

    fxRateTopic.publish(new FxRateEvent(new Instrument(ccyPair, "SPOT"), 0.6543, 0.6545, 1, System.currentTimeMillis()), ccyPair);

    // Verify PriceEntity received the rate
    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var lastRate = componentClient.forEventSourcedEntity(ccyPair)
          .method(PriceEntity::getLastPriceRate)
          .invoke();
      assertThat(lastRate).isPresent();
      assertThat(lastRate.get().bid()).isEqualTo(0.6543);
    });
  }

  @Test
  public void shouldProcessCreditCheckFromTopic() {
    var clientId = "endpoint-client-6";
    var ccyPair = "NZDUSD";

    httpClient
        .POST("/clients/" + clientId + "/subscribe/" + ccyPair)
        .invoke();

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var state = componentClient.forWorkflow(clientId)
          .method(ClientWorkflow::getState)
          .invoke();
      assertThat(state.status()).isEqualTo(ClientWorkflowState.Status.IDLE);
    });

    creditCheckTopic.publish(new CreditStatusEvent(clientId, CreditStatus.OK, "approved", System.currentTimeMillis()), clientId);

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var state = componentClient.forWorkflow(clientId)
          .method(ClientWorkflow::getState)
          .invoke();
      assertThat(state.creditStatus()).isEqualTo(CreditStatus.OK);
    });
  }

  @Test
  public void shouldGetClientState() {
    var clientId = "endpoint-client-7";
    var ccyPair = "EURGBP";

    httpClient
        .POST("/clients/" + clientId + "/subscribe/" + ccyPair)
        .invoke();

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var state = componentClient.forWorkflow(clientId)
          .method(ClientWorkflow::getState)
          .invoke();
      assertThat(state.status()).isEqualTo(ClientWorkflowState.Status.IDLE);
    });

    var response = httpClient
        .GET("/clients/" + clientId + "/state")
        .responseBodyAs(ClientWorkflowState.class)
        .invoke();

    assertThat(response.httpResponse().status().intValue()).isEqualTo(200);
    assertThat(response.body().clientId()).isEqualTo(clientId);
    assertThat(response.body().subscriptions()).contains(ccyPair);
  }

  @Test
  public void shouldSimulateCreditUpdate() {
    var clientId = "endpoint-client-8";
    var ccyPair = "EURJPY";

    httpClient
        .POST("/clients/" + clientId + "/subscribe/" + ccyPair)
        .invoke();

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var state = componentClient.forWorkflow(clientId)
          .method(ClientWorkflow::getState)
          .invoke();
      assertThat(state.status()).isEqualTo(ClientWorkflowState.Status.IDLE);
    });

    var response = httpClient
        .POST("/clients/simulate/credit-update")
        .withRequestBody(new ClientEndpoint.CreditUpdate(clientId, CreditStatus.OK))
        .invoke();

    assertThat(response.httpResponse().status().intValue()).isEqualTo(200);

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var state = componentClient.forWorkflow(clientId)
          .method(ClientWorkflow::getState)
          .invoke();
      assertThat(state.creditStatus()).isEqualTo(CreditStatus.OK);
    });
  }

  @Test
  public void shouldGetPriceRateQuota() {
    var clientId = "endpoint-client-9";
    var ccyPair = "USDCHF";

    httpClient
        .POST("/clients/" + clientId + "/subscribe/" + ccyPair)
        .invoke();

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var state = componentClient.forWorkflow(clientId)
          .method(ClientWorkflow::getState)
          .invoke();
      assertThat(state.status()).isEqualTo(ClientWorkflowState.Status.IDLE);
    });

    awaitClientInView(clientId);

    httpClient
        .POST("/clients/simulate/rate-update")
        .withRequestBody(new ClientEndpoint.RateUpdate(ccyPair, "SPOT", 0.8850, 0.8855, 1, System.currentTimeMillis()))
        .invoke();

    // Get the priceRateId from the entity
    Awaitility.await().ignoreExceptions().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var lastRate = componentClient.forEventSourcedEntity(ccyPair)
          .method(PriceEntity::getLastPriceRate)
          .invoke();
      assertThat(lastRate).isPresent();

      var priceRateId = lastRate.get().priceRateId();
      var response = httpClient
          .GET("/clients/" + clientId + "/price-rate/" + priceRateId + "/quota")
          .responseBodyAs(Quota.class)
          .invoke();

      assertThat(response.httpResponse().status().intValue()).isEqualTo(200);
      assertThat(response.body().clientId()).isEqualTo(clientId);
      assertThat(response.body().ccyPair()).isEqualTo(ccyPair);
      assertThat(response.body().bid()).isEqualTo(0.8850);
    });
  }

  private void awaitClientInView(String clientId) {
    Awaitility.await().atMost(10, TimeUnit.SECONDS).ignoreExceptions().untilAsserted(() -> {
      var entry = componentClient.forView()
          .method(ClientView::getByClientId)
          .invoke(clientId);
      assertThat(entry.clientId()).isEqualTo(clientId);
    });
  }
}