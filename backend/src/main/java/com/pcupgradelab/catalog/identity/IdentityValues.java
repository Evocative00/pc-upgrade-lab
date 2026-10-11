package com.pcupgradelab.catalog.identity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class IdentityValues {
    private IdentityValues() { }

    static String text(String value, String field, int limit) {
        if (value == null || value.isBlank() || value.strip().length() > limit)
            throw new IllegalArgumentException(field + " must be nonblank (max " + limit + " characters)");
        return value.strip();
    }

    static String optional(String value, String field, int limit) {
        return value == null || value.isBlank() ? null : text(value, field, limit);
    }

    static String uuid(String value, String field) {
        String normalized = text(value, field, 36);
        if (normalized.length() != 36) throw new IllegalArgumentException(field + " must be a canonical UUID");
        try {
            String parsed = UUID.fromString(normalized).toString();
            if (!parsed.equalsIgnoreCase(normalized)) throw new IllegalArgumentException();
            return parsed;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(field + " must be a canonical UUID", ex);
        }
    }

    // F/K/G·DDR4·WIFI·PN·하이픈처럼 식별에 필요한 문자열을 지우지 않는다.
    static String alias(String raw) {
        return text(raw, "rawAlias", 500).replace("®", "").replace("™", "")
                .strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static boolean sameJson(Object left, Object right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        if (left instanceof Number a && right instanceof Number b)
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b)
            return a.keySet().equals(b.keySet()) && a.entrySet().stream()
                    .allMatch(entry -> sameJson(entry.getValue(), b.get(entry.getKey())));
        if (left instanceof List<?> a && right instanceof List<?> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!sameJson(a.get(i), b.get(i))) return false;
            return true;
        }
        return left.equals(right);
    }
}
