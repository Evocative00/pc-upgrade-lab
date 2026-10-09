package com.pcupgradelab.catalog.storage;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reviewed excerpts for one motherboard revision. This value never determines SSD compatibility. */
public record MotherboardStorageSupport(RevisionScope revisionScope, String hardwareRevision,
                                        boolean completeDataKnown, String conditions,
                                        List<Slot> slots, List<Source> sources) {
    public MotherboardStorageSupport {
        if (revisionScope == null) throw new IllegalArgumentException("revisionScope is required");
        hardwareRevision = optionalText(hardwareRevision, 48, "hardwareRevision");
        if (revisionScope == RevisionScope.MODEL && hardwareRevision != null) {
            throw new IllegalArgumentException("MODEL scope must not invent a hardware revision");
        }
        if (revisionScope == RevisionScope.EXACT && hardwareRevision == null) {
            throw new IllegalArgumentException("EXACT scope requires hardwareRevision");
        }
        conditions = optionalText(conditions, 2000, "conditions");
        slots = items(slots, "slots");
        sources = items(sources, "sources");
        if (sources.isEmpty()) throw new IllegalArgumentException("At least one supporting source is required");
        unique(slots.stream().map(Slot::slotKey).toList(), "slotKey");
        unique(sources.stream().map(source -> source.sourceUrl() + "/" + source.documentLocation()).toList(), "source location");
        slots = slots.stream().sorted(Comparator.comparing(Slot::slotKey)).toList();
        sources = sources.stream().sorted(Comparator.comparing(Source::sourceUrl)
                .thenComparing(source -> source.documentLocation() == null ? "" : source.documentLocation())).toList();
    }

    public String revisionKey() { return revisionScope == RevisionScope.MODEL ? "MODEL" : "REV:" + hardwareRevision; }
    public enum RevisionScope { MODEL, EXACT }
    public enum RuleEffect { DISABLED, LANES_REDUCED, PORT_SHARED, CONDITIONAL_SUPPORT, UNSTRUCTURED }

    public record Slot(String slotKey, String connectorType, String connectorKey,
                       List<String> supportedLengthCodes, List<String> supportedBusInterfaces,
                       List<String> supportedProtocols, String maxPcieVersion, Integer maxPcieLanes,
                       String sataVersion, String laneSource, Boolean nvmeBootSupport,
                       String notes, List<Rule> rules) {
        public Slot {
            slotKey = requiredText(slotKey, 64, "slotKey");
            connectorType = requiredCode(connectorType, "connectorType", Set.of("M2", "SATA"));
            connectorKey = code(connectorKey, "connectorKey", Set.of("B", "M", "B_M"));
            supportedLengthCodes = codes(supportedLengthCodes, "supportedLengthCodes",
                    Set.of("2230", "2242", "2260", "2280", "22110"));
            supportedBusInterfaces = codes(supportedBusInterfaces, "supportedBusInterfaces", Set.of("PCIE", "SATA"));
            supportedProtocols = codes(supportedProtocols, "supportedProtocols", Set.of("NVME", "ATA"));
            maxPcieVersion = version(maxPcieVersion, "maxPcieVersion");
            if (maxPcieLanes != null && !Set.of(1, 2, 4, 8, 16, 32).contains(maxPcieLanes)) {
                throw new IllegalArgumentException("maxPcieLanes is not a supported PCIe lane count");
            }
            sataVersion = version(sataVersion, "sataVersion");
            laneSource = code(laneSource, "laneSource", Set.of("CPU", "CHIPSET"));
            notes = optionalText(notes, 2000, "slot notes");
            rules = items(rules, "rules");
            unique(rules.stream().map(Rule::ruleKey).toList(), "ruleKey");
            rules = rules.stream().sorted(Comparator.comparing(Rule::ruleKey)).toList();
            if ("SATA".equals(connectorType)
                    && (connectorKey != null || !supportedLengthCodes.isEmpty() || maxPcieVersion != null
                    || maxPcieLanes != null || supportedBusInterfaces.contains("PCIE")
                    || supportedProtocols.contains("NVME") || nvmeBootSupport != null)) {
                throw new IllegalArgumentException("A SATA connector cannot have M.2/PCIe/NVMe fields");
            }
            if ((maxPcieVersion != null || maxPcieLanes != null || supportedProtocols.contains("NVME")
                    || nvmeBootSupport != null) && !supportedBusInterfaces.contains("PCIE")) {
                throw new IllegalArgumentException("PCIe/NVMe facts require confirmed PCIe bus support");
            }
            if (nvmeBootSupport != null && !supportedProtocols.contains("NVME")) {
                throw new IllegalArgumentException("nvmeBootSupport requires confirmed NVMe protocol support");
            }
            if ((sataVersion != null || supportedProtocols.contains("ATA")) && !supportedBusInterfaces.contains("SATA")) {
                throw new IllegalArgumentException("SATA/ATA facts require confirmed SATA bus support");
            }
            // Empty lists mean no excerpted facts, not a confirmed lack of support.
        }
    }

    public record Rule(String ruleKey, String cpuCondition, String biosCondition, RuleEffect effect,
                       String affectedSlotKey, String rawCondition) {
        public Rule {
            ruleKey = requiredText(ruleKey, 64, "ruleKey");
            cpuCondition = optionalText(cpuCondition, 1000, "cpuCondition");
            biosCondition = optionalText(biosCondition, 1000, "biosCondition");
            if (effect == null) throw new IllegalArgumentException("rule effect is required");
            affectedSlotKey = optionalText(affectedSlotKey, 64, "affectedSlotKey");
            rawCondition = requiredText(rawCondition, 4000, "rawCondition");
        }
    }

    public record Source(String sourceUrl, Instant checkedAt, String sourceRevision,
                         String documentLocation, String supportedFacts) {
        public Source {
            sourceUrl = requiredText(sourceUrl, 2048, "sourceUrl");
            var uri = URI.create(sourceUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("sourceUrl must be an HTTPS URL without credentials");
            }
            if (checkedAt == null) throw new IllegalArgumentException("checkedAt is required");
            checkedAt = checkedAt.truncatedTo(ChronoUnit.MICROS);
            sourceRevision = optionalText(sourceRevision, 255, "sourceRevision");
            documentLocation = optionalText(documentLocation, 255, "documentLocation");
            supportedFacts = requiredText(supportedFacts, 4000, "supportedFacts");
        }
    }

    private static String optionalText(String value, int length, String field) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > length) throw new IllegalArgumentException(field + " is too long");
        return normalized;
    }
    private static String requiredText(String value, int length, String field) {
        String normalized = optionalText(value, length, field);
        if (normalized == null) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
    private static String code(String value, String field, Set<String> allowed) {
        String normalized = optionalText(value, 32, field);
        if (normalized == null) return null;
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new IllegalArgumentException(field + " has an unsupported value");
        return normalized;
    }
    private static String requiredCode(String value, String field, Set<String> allowed) {
        String normalized = code(value, field, allowed);
        if (normalized == null) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
    private static String version(String value, String field) {
        String normalized = optionalText(value, 16, field);
        if (normalized != null && !normalized.matches("[1-9][0-9]?\\.[0-9]{1,2}[a-z]?")) {
            throw new IllegalArgumentException(field + " must preserve a published version");
        }
        return normalized;
    }
    private static <T> List<T> items(List<T> values, String field) {
        if (values == null || values.stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException(field + " must be a non-null list without null elements");
        }
        return List.copyOf(values);
    }
    private static List<String> codes(List<String> values, String field, Set<String> allowed) {
        List<String> normalized = items(values, field).stream().map(value -> requiredCode(value, field, allowed)).toList();
        unique(normalized, field);
        return normalized.stream().sorted().toList();
    }
    private static void unique(List<String> values, String field) {
        if (new HashSet<>(values).size() != values.size()) throw new IllegalArgumentException(field + " must be distinct");
    }
}
