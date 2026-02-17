package com.example.perf;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.time.Duration;
import java.util.LongSummaryStatistics;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gatling simulation for end-to-end FX Trading Platform performance testing.
 *
 * <p>Three concurrent scenarios:
 * <ol>
 *   <li><b>Client Setup & SSE Listeners</b> - N clients subscribe to a currency pair, set credit to OK,
 *       then listen for quota events via SSE and push them to a shared QuotaStore.</li>
 *   <li><b>Rate Feeder</b> - A single user sends price rate updates at a configured pace,
 *       generating known quotaIds to enable round-trip latency measurement.</li>
 *   <li><b>Trade Acceptor</b> - A single user periodically picks a random quota from the store
 *       and submits a trade, waiting for the TradeNotification via SSE.</li>
 * </ol>
 *
 * <p>Run with:
 * <pre>mvn gatling:test -pl performance-tests -DUSERS=5 -DDURATION=120</pre>
 */
public class TradingPlatformSimulation extends Simulation {

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
  private static final AtomicBoolean SSE_FORMAT_LOGGED = new AtomicBoolean(false);

  // Track quotaId -> send timestamp for round-trip latency measurement
  private static final ConcurrentHashMap<String, Long> SEND_TIMESTAMPS = new ConcurrentHashMap<>();
  // Collect all measured latencies for summary reporting
  private static final ConcurrentLinkedQueue<Long> RATE_TO_QUOTA_LATENCIES = new ConcurrentLinkedQueue<>();

  // --- SSE check: capture raw message for Java-side parsing ---
  private static final SseMessageCheck quotaCheck = sse.checkMessage("quota_received")
      .matching(substring("quotaId"))
      .check(regex("(?s)(.+)").saveAs("rawQuota"));

  private static final SseMessageCheck tradeCheck = sse.checkMessage("trade_notification")
      .matching(substring("tradeId"))
      .check(regex("(?s)(.+)").saveAs("rawTrade"));

  // --- HTTP protocol configs ---
  private final HttpProtocolBuilder pricingProtocol = http
      .baseUrl(PRICING_BASE_URL)
      .acceptHeader("application/json")
      .contentTypeHeader("application/json");

  private final HttpProtocolBuilder tradingProtocol = http
      .baseUrl(TRADING_BASE_URL)
      .acceptHeader("application/json")
      .contentTypeHeader("application/json");

  // --- Scenario 1: Client Setup & SSE Listeners ---
  private final ScenarioBuilder clientScenario = scenario("Client Setup & SSE Listeners")
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
      .pause(1)
      // Open SSE stream and collect quotas for the test duration
      .exec(
          sse("SSE Connect")
              .get(session -> "/clients/" + session.getString("clientId") + "/notifications")
      )
      .pause(1) // allow SSE connection to establish before setting checks
      // Loop: wait for each SSE quota message, capture raw, parse in Java
      .during(Duration.ofSeconds(DURATION_SECS)).on(
          exec(
              sse("Await quota").setCheck()
                  .await(30).on(quotaCheck)
          )
          .exec(session -> {
            var raw = session.getString("rawQuota");
            // Log the first SSE message to understand the format
            if (raw != null && SSE_FORMAT_LOGGED.compareAndSet(false, true)) {
              System.out.println("[PERF] SSE quota message format: " + raw);
            }
            if (raw != null) {
              var quotaId = extractJsonField(raw, "quotaId");
              var clientId = extractJsonField(raw, "clientId");
              if (quotaId != null && clientId != null) {
                QuotaStore.add(new QuotaStore.QuotaInfo(quotaId, clientId));

                // Compute round-trip latency if this quotaId was sent by the rate feeder
                var sendTs = SEND_TIMESTAMPS.remove(quotaId);
                if (sendTs != null) {
                  long latencyMs = System.currentTimeMillis() - sendTs;
                  RATE_TO_QUOTA_LATENCIES.add(latencyMs);
                }
              }
            }
            return session;
          })
      )
      // Close SSE connection
      .exec(sse("SSE Close").close());

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
            // Track send timestamp for round-trip latency
            SEND_TIMESTAMPS.put(quotaId, tsMs);
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

  // --- Scenario 3: Trade Acceptor ---
  private final ScenarioBuilder tradeAcceptorScenario = scenario("Trade Acceptor")
      // Wait for warm-up: clients subscribe and receive first quotas
      .pause(10)
      .during(Duration.ofSeconds(DURATION_SECS - 10)).on(
          exec(session -> {
            var quota = QuotaStore.pollRandom();
            if (quota != null) {
              return session
                  .set("quotaId", quota.quotaId())
                  .set("tradeClientId", quota.clientId())
                  .set("tradeId", quota.clientId() + "_" + quota.quotaId())
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
              // Open SSE to wait for trade notification
              .exec(
                  sse("Trade SSE Connect")
                      .get(session -> "/trades/" + session.getString("tradeId") + "/notifications")
                      .await(30).on(tradeCheck)
              )
              .exec(sse("Trade SSE Close").close())
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

  @Override
  public void after() {
    var latencies = new java.util.ArrayList<>(RATE_TO_QUOTA_LATENCIES);
    if (latencies.isEmpty()) {
      System.out.println("\n[PERF] No rate-to-quota round-trip latencies measured.");
      return;
    }
    latencies.sort(Long::compareTo);
    var stats = latencies.stream().mapToLong(Long::longValue).summaryStatistics();
    long p50 = latencies.get((int) (latencies.size() * 0.50));
    long p75 = latencies.get((int) (latencies.size() * 0.75));
    long p95 = latencies.get(Math.min((int) (latencies.size() * 0.95), latencies.size() - 1));
    long p99 = latencies.get(Math.min((int) (latencies.size() * 0.99), latencies.size() - 1));

    System.out.println("\n================================================================================");
    System.out.println("  RATE-TO-QUOTA ROUND-TRIP LATENCY (rate_update POST -> quota SSE arrival)");
    System.out.println("================================================================================");
    System.out.printf("  Count:  %d%n", stats.getCount());
    System.out.printf("  Min:    %d ms%n", stats.getMin());
    System.out.printf("  Mean:   %.1f ms%n", stats.getAverage());
    System.out.printf("  p50:    %d ms%n", p50);
    System.out.printf("  p75:    %d ms%n", p75);
    System.out.printf("  p95:    %d ms%n", p95);
    System.out.printf("  p99:    %d ms%n", p99);
    System.out.printf("  Max:    %d ms%n", stats.getMax());
    System.out.println("================================================================================\n");
  }

  // --- Utility: extract a string field value from JSON (handles escaped quotes) ---
  static String extractJsonField(String text, String field) {
    if (text == null) return null;
    // Look for "field" or escaped \"field\" patterns
    String[] patterns = {
        "\"" + field + "\"",    // standard: "quotaId"
        "\\\"" + field + "\\\"" // escaped: \"quotaId\"
    };
    for (var pattern : patterns) {
      int idx = text.indexOf(pattern);
      if (idx < 0) continue;
      // Find the colon after the key
      int afterKey = idx + pattern.length();
      int colonIdx = text.indexOf(':', afterKey);
      if (colonIdx < 0) continue;
      int start = colonIdx + 1;
      // Skip whitespace
      while (start < text.length() && (text.charAt(start) == ' ' || text.charAt(start) == '\t')) start++;
      if (start >= text.length()) continue;

      // Detect value delimiter (regular or escaped quotes)
      if (text.startsWith("\\\"", start)) {
        // Escaped quote value: \"value\"
        int valStart = start + 2;
        int valEnd = text.indexOf("\\\"", valStart);
        if (valEnd > valStart) return text.substring(valStart, valEnd);
      } else if (text.charAt(start) == '"') {
        // Standard quote value: "value"
        int valStart = start + 1;
        int valEnd = text.indexOf('"', valStart);
        if (valEnd > valStart) return text.substring(valStart, valEnd);
      } else {
        // Unquoted value (number, boolean, null)
        int end = start;
        while (end < text.length() && text.charAt(end) != ',' && text.charAt(end) != '}' && text.charAt(end) != '\\') end++;
        if (end > start) return text.substring(start, end).trim();
      }
    }
    return null;
  }
}
