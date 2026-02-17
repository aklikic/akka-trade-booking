package com.example.perf;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Thread-safe shared buffer for quota information collected from SSE streams.
 * SSE listener scenarios add quotas; the trade acceptor scenario polls them.
 */
public final class QuotaStore {

  public record QuotaInfo(String quotaId, String clientId) {}

  private static final int MAX_SIZE = 100;

  private static final ConcurrentLinkedQueue<QuotaInfo> STORE = new ConcurrentLinkedQueue<>();

  public static void add(QuotaInfo info) {
    STORE.add(info);
    // Evict oldest entries if over capacity
    while (STORE.size() > MAX_SIZE) {
      STORE.poll();
    }
  }

  /**
   * Polls a random quota from the store, or null if empty.
   */
  public static QuotaInfo pollRandom() {
    var snapshot = new ArrayList<>(STORE);
    if (snapshot.isEmpty()) {
      return null;
    }
    int idx = ThreadLocalRandom.current().nextInt(snapshot.size());
    var picked = snapshot.get(idx);
    STORE.remove(picked);
    return picked;
  }

  public static void clear() {
    STORE.clear();
  }
}
