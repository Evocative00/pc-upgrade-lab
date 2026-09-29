package com.pcupgradelab.catalog;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 출처 JSON의 중첩 값과 null을 보존하면서 호출자가 원본을 변경하지 못하도록 복사한다. */
final class CatalogJsonValues {
    private CatalogJsonValues() { }

    static Map<String, Object> immutableObject(Map<String, Object> source) {
        return source == null ? null : copyObject(source, new IdentityHashMap<>());
    }

    private static Map<String, Object> copyObject(Map<?, ?> source,
                                                 IdentityHashMap<Object, Boolean> ancestors) {
        enter(source, ancestors);
        try {
            var copy = new LinkedHashMap<String, Object>();
            for (var entry : source.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("rawPayload object keys must be non-null strings");
                }
                copy.put(key, copyValue(entry.getValue(), ancestors));
            }
            // Map.copyOf는 JSON에서 허용하는 null 값을 거부하므로 사용하지 않는다.
            return Collections.unmodifiableMap(copy);
        } finally {
            ancestors.remove(source);
        }
    }

    private static List<Object> copyArray(List<?> source, IdentityHashMap<Object, Boolean> ancestors) {
        enter(source, ancestors);
        try {
            var copy = new ArrayList<Object>(source.size());
            for (var value : source) {
                copy.add(copyValue(value, ancestors));
            }
            return Collections.unmodifiableList(copy);
        } finally {
            ancestors.remove(source);
        }
    }

    private static Object copyValue(Object value, IdentityHashMap<Object, Boolean> ancestors) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Double number && Double.isFinite(number)) {
            return value;
        }
        if (value instanceof Float number && Float.isFinite(number)) {
            return value;
        }
        if (value instanceof Map<?, ?> object) {
            return copyObject(object, ancestors);
        }
        if (value instanceof List<?> array) {
            return copyArray(array, ancestors);
        }
        throw new IllegalArgumentException("rawPayload must contain only JSON values and finite numbers");
    }

    private static void enter(Object value, IdentityHashMap<Object, Boolean> ancestors) {
        if (ancestors.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("rawPayload must not contain circular references");
        }
    }
}
