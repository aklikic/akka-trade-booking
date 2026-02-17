package com.example.perf;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gatling simulation that only sends price rate updates at a configured pace.
 *
 * <p>Assumes clients are already subscribed (e.g. via {@link SubscribeSimulation}).
 * Useful for benchmarking the rate update pipeline in isolation:
 * PriceEntity -> PriceRateQuotasCalculationConsumer -> ClientWorkflow -> QuotaEntity -> QuotaNotificationConsumer -> SSE.
 *
 * <p>Run with:
 * <pre>mvn gatling:test -pl performance-tests -Dgatling.simulationClass=com.example.perf.PriceRateSimulation</pre>
 */
public class PriceRateSimulation extends Simulation {

  private static final int DURATION_SECS = Integer.getInteger("DURATION", 120);
  private static final int RATE_PER_SEC = Integer.getInteger("RATE_PER_SEC", 4);
  private static final String TENANT = System.getProperty("TENANT", "");
  private static final String CCY_PAIR = TENANT.isEmpty()
      ? System.getProperty("CCY_PAIR", "EURUSD")
      : TENANT + "-" + System.getProperty("CCY_PAIR", "EURUSD");
  private static final String PRICING_BASE_URL = System.getProperty("PRICING_BASE_URL", "http://localhost:9001");

  private static final double BASE_BID = 1.1050;
  private static final double BASE_ASK = 1.1055;

  private static final AtomicLong SEQ = new AtomicLong(1);

  private final HttpProtocolBuilder pricingProtocol = http
      .baseUrl(PRICING_BASE_URL)
      .acceptHeader("application/json")
      .contentTypeHeader("application/json");

  private final ScenarioBuilder rateFeederScenario = scenario("Rate Feeder")
      .during(Duration.ofSeconds(DURATION_SECS)).on(
          exec(session -> {
            long seq = SEQ.getAndIncrement();
            double jitter = (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.001;
            double bid = BASE_BID + jitter;
            double ask = BASE_ASK + jitter;
            long tsMs = System.currentTimeMillis();
            return session
                .set("seq", seq)
                .set("bid", String.format("%.4f", bid))
                .set("ask", String.format("%.4f", ask))
                .set("tsMs", tsMs);
          })
          .exec(
              http("rate_update")
                  .post("/clients/simulate/rate-update")
                  .body(StringBody(session ->
                      "{\"ccyPair\":\"" + CCY_PAIR + "\","
                          + "\"tenor\":\"SPOT\","
                          + "\"bid\":" + session.getString("bid") + ","
                          + "\"ask\":" + session.getString("ask") + ","
                          + "\"seq\":" + session.getLong("seq") + ","
                          + "\"tsMs\":" + session.getLong("tsMs") + "}"))
          )
          .pace(Duration.ofMillis(1000 / RATE_PER_SEC))
      );

  {
    setUp(
        rateFeederScenario.injectOpen(atOnceUsers(1))
            .protocols(pricingProtocol)
    );
  }
}