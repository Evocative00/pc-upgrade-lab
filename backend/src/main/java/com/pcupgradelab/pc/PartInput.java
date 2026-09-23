package com.pcupgradelab.pc;

import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared input contract. Quantity counts devices; capacityBytes is per device. */
public record PartInput(
        @NotNull PartType type,
        @NotBlank @Size(max = 255) String displayName,
        @Size(max = 500) String rawName,
        @NotNull @Min(1) @Max(64) Integer quantity,
        @NotNull InputSource source,
        @Size(max = 128) String catalogProductId,
        @NotNull MatchStatus matchStatus,
        @NotNull @Size(max = 40) Map<String, Object> specs) {

    public PartInput {
        if (specs != null) specs = Collections.unmodifiableMap(new LinkedHashMap<>(specs));
    }

    @JsonIgnore
    @AssertTrue(message = "catalogProductId and matchStatus must agree")
    public boolean isCatalogLinkValid() {
        return matchStatus == MatchStatus.UNMATCHED ? catalogProductId == null
                : matchStatus == MatchStatus.MATCHED && catalogProductId != null && !catalogProductId.isBlank();
    }

    @JsonIgnore
    @AssertTrue(message = "specs must contain bounded scalar values; known capacities must be positive")
    public boolean isSpecsValid() {
        if (specs == null || specs.size() > 40) return false;
        for (var entry : specs.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            if (key == null || !key.matches("[a-zA-Z][a-zA-Z0-9]{0,49}")) return false;
            if (value != null && !(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean)) return false;
            if (value instanceof String text && text.length() > 500) return false;
            if (value instanceof Number number && !Double.isFinite(number.doubleValue())) return false;
            if ((key.equals("capacityBytes") || key.equals("vramBytes")) && value != null
                    && (!(value instanceof Number number) || number.doubleValue() <= 0)) return false;
        }
        return true;
    }
}
