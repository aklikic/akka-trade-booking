package com.example.perf;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simplified Gatling simulation for the FX Trading Platform.
 *
 * <p>Unlike {@link TradingPlatformSimulation}, this version does NOT use SSE streams
 * and does NOT measure end-to-end latency. It focuses on HTTP request throughput:
 * subscribe, rate updates, and trade acceptance with GET-based verification.
 *
 * <p>Three concurrent scenarios:
 * <ol>
 *   <li><b>Client Setup</b> - N clients subscribe to a currency pair and set credit to OK.</li>
 *   <li><b>Rate Feeder</b> - A single user sends price rate updates at a configured pace.</li>
 *   <li><b>Trade Acceptor</b> - A single user periodically accepts a quote and verifies
 *       the trade via GET /trades/{tradeId}.</li>
 * </ol>
 *
 * <p>Run with:
 * <pre>mvn gatling:test -pl performance-tests -Dgatling.simulationClass=com.example.perf.SimpleTradingSimulation</pre>
 */
public class SimpleTradingSimulation extends Simulation {

  // --- Configurable parameters (system properties with defaults) ---
  private static final int USERS = Integer.getInteger("USERS", 10);
  private static final int DURATION_SECS = Integer.getInteger("DURATION", 120);
  private static final int RATE_PER_SEC = Integer.getInteger("RATE_PER_SEC", 4);
  private static final int ACCEPT_INTERVAL_SECS = Integer.getInteger("ACCEPT_INTERVAL", 60);
  private static final String TENANT = System.getProperty("TENANT", "");
  private static final String CCY_PAIR = TENANT.isEmpty()
      ? System.getProperty("CCY_PAIR", "EURUSD")
      : TENANT + "-" + System.getProperty("CCY_PAIR", "EURUSD");
  private static final String PRICING_BASE_URL = System.getProperty("PRICING_BASE_URL", "http://localhost:9001");
  private static final String TRADING_BASE_URL = System.getProperty("TRADING_BASE_URL", "http://localhost:9002");

  private static final double BASE_BID = 1.1050;
  private static final double BASE_ASK = 1.1055;

  private static final AtomicLong SEQ = new AtomicLong(1);

  // Shared queue of sent quotaIds for the trade acceptor to pick from
  private static final ConcurrentLinkedQueue<String> SENT_QUOTA_IDS = new ConcurrentLinkedQueue<>();
  private static final int MAX_QUOTA_IDS = 100;

  // --- HTTP protocol configs ---
  private final HttpProtocolBuilder pricingProtocol = http
      .baseUrl(PRICING_BASE_URL)
      .acceptHeader("application/json")
      .contentTypeHeader("application/json");

  private final HttpProtocolBuilder tradingProtocol = http
      .baseUrl(TRADING_BASE_URL)
      .acceptHeader("application/json")
      .contentTypeHeader("application/json");

  // --- Scenario 1: Client Setup (no SSE) ---
  private final ScenarioBuilder clientScenario = scenario("Client Setup")
      .exec(session -> {
        var prefix = TENANT.isEmpty() ? "perf-client-" : TENANT + "-client-";
        return session.set("clientId", prefix + session.userId());
      })
      // Subscribe to currency pair
      .exec(
          http("subscribe")
              .post(session -> "/clients/" + session.getString("clientId") + "/subscribe/" + CCY_PAIR)
      )
      // Set credit status to OK
      .exec(
          http("credit_update")
              .post("/clients/simulate/credit-update")
              .body(StringBody(session ->
                  "{\"clientId\":\"" + session.getString("clientId") + "\",\"status\":\"OK\"}"))
      )
      // Just wait for the test duration (no SSE listening)
      .pause(Duration.ofSeconds(DURATION_SECS));

  // --- Scenario 2: Rate Feeder ---
  private final ScenarioBuilder rateFeederScenario = scenario("Rate Feeder")
      .during(Duration.ofSeconds(DURATION_SECS)).on(
          exec(session -> {
            long seq = SEQ.getAndIncrement();
            double jitter = (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.001;
            double bid = BASE_BID + jitter;
            double ask = BASE_ASK + jitter;
            long tsMs = System.currentTimeMillis();
            String quotaIdPrefix = TENANT.isEmpty() ? "perf-q-" : TENANT + "-q-";
            String quotaId = quotaIdPrefix + seq;
            // Track quotaId for trade acceptor
            SENT_QUOTA_IDS.add(quotaId);
            while (SENT_QUOTA_IDS.size() > MAX_QUOTA_IDS) {
              SENT_QUOTA_IDS.poll();
            }
            return session
                .set("seq", seq)
                .set("bid", String.format("%.4f", bid))
                .set("ask", String.format("%.4f", ask))
                .set("tsMs", tsMs)
                .set("quotaId", quotaId);
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
                          + "\"tsMs\":" + session.getLong("tsMs") + ","
                          + "\"quotaId\":\"" + session.getString("quotaId") + "\"}"))
          )
          .pace(Duration.ofMillis(1000 / RATE_PER_SEC))
      );

  // --- Scenario 3: Trade Acceptor (no SSE, uses GET to verify) ---
  private final ScenarioBuilder tradeAcceptorScenario = scenario("Trade Acceptor")
      // Wait for warm-up: clients subscribe and rate updates start flowing
      .pause(15)
      .during(Duration.ofSeconds(DURATION_SECS - 15)).on(
          exec(session -> {
            var quotaId = SENT_QUOTA_IDS.poll();
            if (quotaId != null) {
              // Pick a random client
              var prefix = TENANT.isEmpty() ? "perf-client-" : TENANT + "-client-";
              var clientId = prefix + (ThreadLocalRandom.current().nextInt(USERS) + 1);
              var tradeId = clientId + "_" + quotaId;
              return session
                  .set("quotaId", quotaId)
                  .set("tradeClientId", clientId)
                  .set("tradeId", tradeId)
                  .set("hasQuota", true);
            } else {
              return session.set("hasQuota", false);
            }
          })
          .doIf(session -> session.getBoolean("hasQuota")).then(
              exec(
                  http("accept_quote")
                      .post("/trades/accept")
                      .body(StringBody(session ->
                          "{\"quotaId\":\"" + session.getString("quotaId") + "\","
                              + "\"clientId\":\"" + session.getString("tradeClientId") + "\","
                              + "\"side\":\"BUY\","
                              + "\"quantity\":1000000}"))
              )
              // Poll trade status via GET (allow time for workflow to complete)
              .pause(2)
              .exec(
                  http("get_trade")
                      .get(session -> "/trades/" + session.getString("tradeId"))
              )
          )
          .pace(Duration.ofSeconds(ACCEPT_INTERVAL_SECS))
      );

  // --- Simulation setup ---
  {
    setUp(
        clientScenario.injectOpen(atOnceUsers(USERS))
            .protocols(pricingProtocol),
        rateFeederScenario.injectOpen(atOnceUsers(1))
            .protocols(pricingProtocol),
        tradeAcceptorScenario.injectOpen(atOnceUsers(1))
            .protocols(tradingProtocol)
    );
  }
}