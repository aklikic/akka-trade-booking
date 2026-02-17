package com.example.domain;

import java.util.ArrayList;
import java.util.List;

public record QuotaViewQueryState(List<String> ccyPairs) {
    public static QuotaViewQueryState empty() {
        return new QuotaViewQueryState(List.of());
    }

    public QuotaViewQueryState addCcyPair(String ccyPair) {
        var updated = new ArrayList<>(ccyPairs);
        updated.add(ccyPair);
        return new QuotaViewQueryState(List.copyOf(updated));
    }
    public QuotaViewQueryState removeCcyPair(String ccyPair) {
        var updated = new ArrayList<>(ccyPairs);
        updated.remove(ccyPair);
        return new QuotaViewQueryState(List.copyOf(updated));
    }
    public boolean containsCcyPair(String ccyPair) {
        return ccyPairs.contains(ccyPair);
    }
}
