package com.example.perf;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

/**
 * Minimal Gatling simulation that unsubscribes N clients from a currency pair, then exits.
 *
 * <p>Useful for tearing down subscriptions created by {@link SubscribeSimulation},
 * or for benchmarking unsubscription throughput in isolation.
 *
 * <p>Run with:
 * <pre>mvn gatling:test -pl performance-tests -Dgatling.simulationClass=com.example.perf.UnsubscribeSimulation</pre>
 */
public class UnsubscribeSimulation extends Simulation {

  private static final int USERS = Integer.getInteger("USERS", 10);
  private static final String TENANT = System.getProperty("TENANT", "");
  private static final String CLIENT_TENANT = System.getProperty("CLIENT_TENANT", TENANT);
  private static final String CCY_PAIR = TENANT.isEmpty()
      ? System.getProperty("CCY_PAIR", "EURUSD")
      : TENANT + "-" + System.getProperty("CCY_PAIR", "EURUSD");
  private static final String PRICING_BASE_URL = System.getProperty("PRICING_BASE_URL", "http://localhost:9001");

  private final HttpProtocolBuilder pricingProtocol = http
      .baseUrl(PRICING_BASE_URL)
      .acceptHeader("application/json")
      .contentTypeHeader("application/json");

  private final ScenarioBuilder unsubscribeScenario = scenario("Unsubscribe Clients")
      .exec(session -> {
        var clientTenant = CLIENT_TENANT.isEmpty() ? TENANT : CLIENT_TENANT;
        var prefix = clientTenant.isEmpty() ? "perf-client-" : clientTenant + "-client-";
        return session.set("clientId", prefix + session.userId());
      })
      .exec(
          http("unsubscribe")
              .post(session -> "/clients/" + session.getString("clientId") + "/unsubscribe/" + CCY_PAIR)
      );

  {
    setUp(
        unsubscribeScenario.injectOpen(atOnceUsers(USERS))
            .protocols(pricingProtocol)
    );
  }
}