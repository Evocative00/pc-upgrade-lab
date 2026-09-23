package com.pcupgradelab.scan;

import com.pcupgradelab.pc.PartInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ScanDtos {
    private ScanDtos() { }

    public record Created(UUID sessionId, String readToken, String launchUri, Instant expiresAt) { }
    public record Warning(@NotBlank @Size(max = 20) String scope,
                          @NotBlank @Size(max = 50) String code,
                          @NotBlank @Size(max = 300) String message) { }
    public record Result(@NotNull @Min(1) @Max(1) Integer schemaVersion,
                         @NotBlank @Size(max = 30) String collectorVersion,
                         @NotNull Instant collectedAt,
                         @NotNull @Size(max = 64) List<@Valid @NotNull PartInput> parts,
                         @NotNull @Size(max = 30) List<@Valid @NotNull Warning> warnings) {
        public Result {
            if (parts != null) parts = List.copyOf(parts);
            if (warnings != null) warnings = List.copyOf(warnings);
        }
    }
    public record Failure(@NotBlank @Size(max = 50) String code,
                          @NotBlank @Size(max = 300) String message) { }
    public record View(UUID sessionId, ScanStatus status, Instant expiresAt, Result result, Failure failure) { }
}
