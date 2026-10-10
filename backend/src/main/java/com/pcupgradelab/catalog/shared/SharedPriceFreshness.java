package com.pcupgradelab.catalog.shared;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import static com.pcupgradelab.catalog.shared.SharedPriceDtos.Freshness;

/** The same explicit clock and thresholds are used by the publisher and local adapter. */
public record SharedPriceFreshness(Duration staleAfter, Duration expireAfter) {
    public SharedPriceFreshness {
        Objects.requireNonNull(staleAfter); Objects.requireNonNull(expireAfter);
        if (staleAfter.isNegative() || staleAfter.isZero() || expireAfter.compareTo(staleAfter) <= 0
                || staleAfter.getNano() != 0 || expireAfter.getNano() != 0)
            throw new IllegalArgumentException("Expiry must follow positive whole-second stale threshold");
    }
    public static SharedPriceFreshness defaults() { return new SharedPriceFreshness(Duration.ofHours(48), Duration.ofDays(7)); }
    public SharedPriceDtos.Policy toView() { return new SharedPriceDtos.Policy(staleAfter.toSeconds(), expireAfter.toSeconds()); }
    public Freshness classify(Instant observedAt, Instant now) {
        if (observedAt == null) return Freshness.NO_PRICE;
        if (observedAt.isAfter(now)) throw new IllegalArgumentException("Future price observation is invalid");
        var age = Duration.between(observedAt, now);
        return age.compareTo(expireAfter) >= 0 ? Freshness.EXPIRED
                : age.compareTo(staleAfter) >= 0 ? Freshness.STALE : Freshness.FRESH;
    }
}
