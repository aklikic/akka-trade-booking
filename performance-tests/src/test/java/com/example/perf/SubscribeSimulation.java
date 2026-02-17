package com.example.perf;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

/**
 * Minimal Gatling simulation that only subscribes N clients to a currency pair
 * and sets their credit status to OK, then exits.
 *
 * <p>Useful for pre-populating the system with subscriptions before running
 * other tests, or for benchmarking subscription throughput in isolation.
 *
 * <p>Run with:
 * <pre>mvn gatling:test -pl performance-tests -Dgatling.simulationClass=com.example.perf.SubscribeSimulation</pre>
 */
public class SubscribeSimulation extends Simulation {

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

  private final ScenarioBuilder subscribeScenario = scenario("Subscribe Clients")
      .exec(session -> {
        var clientTenant = CLIENT_TENANT.isEmpty() ? TENANT : CLIENT_TENANT;
        var prefix = clientTenant.isEmpty() ? "perf-client-" : clientTenant + "-client-";
        return session.set("clientId", prefix + session.userId());
      })
      .exec(
          http("subscribe")
              .post(session -> "/clients/" + session.getString("clientId") + "/subscribe/" + CCY_PAIR)
      )
      .exec(
          http("credit_update")
              .post("/clients/simulate/credit-update")
              .body(StringBody(session ->
                  "{\"clientId\":\"" + session.getString("clientId") + "\",\"status\":\"OK\"}"))
      );

  {
    setUp(
        subscribeScenario.injectOpen(atOnceUsers(USERS))
            .protocols(pricingProtocol)
    );
  }
}