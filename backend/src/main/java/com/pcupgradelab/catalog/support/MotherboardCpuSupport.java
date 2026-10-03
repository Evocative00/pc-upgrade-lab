package com.pcupgradelab.catalog.support;

import java.net.URI;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 모델/보드 리비전별 제조사 목록 발췌. BIOS 문자열의 순서를 비교하거나 호환 판정을 만들지 않는다. */
public record MotherboardCpuSupport(RevisionScope revisionScope, String hardwareRevision,
                                    String conditions, List<Entry> entries) {
    public MotherboardCpuSupport {
        if (revisionScope == null || entries == null) throw new IllegalArgumentException("scope and entries are required");
        text(conditions, 2000, "profile conditions");
        if (revisionScope == RevisionScope.MODEL && hardwareRevision != null) {
            throw new IllegalArgumentException("MODEL scope must not invent a hardware revision");
        }
        if (revisionScope == RevisionScope.EXACT) text(hardwareRevision, 48, "hardware revision");
        var seen = new HashSet<String>();
        for (var entry : entries) {
            if (entry == null || !seen.add(entry.cpuProductId() + "/" + entry.variantKey())) {
                throw new IllegalArgumentException("CPU variants must be distinct within the revision");
            }
        }
        entries = entries.stream().sorted(Comparator.comparing(Entry::cpuProductId).thenComparing(Entry::variantKey)).toList();
    }

    public String revisionKey() { return revisionScope == RevisionScope.MODEL ? "MODEL" : "REV:" + hardwareRevision; }

    public enum RevisionScope { MODEL, EXACT }
    public enum SupportStatus { LISTED, UNVERIFIED }
    public enum BiosRequirement { VERSION, ALL, UNKNOWN }

    public record Entry(String cpuProductId, String variantKey, SupportStatus supportStatus,
                        String reportedCpuName, String cpuStepping, BiosRequirement biosRequirement,
                        String minimumBiosVersion, String manufacturerBiosLabel, String sourceUrl, String conditions) {
        public Entry {
            text(cpuProductId, 128, "CPU ID");
            text(variantKey, 64, "variant key");
            text(reportedCpuName, 255, "reported CPU name");
            text(conditions, 2000, "entry conditions");
            if (cpuStepping != null) text(cpuStepping, 64, "CPU stepping");
            if (minimumBiosVersion != null) text(minimumBiosVersion, 64, "minimum BIOS version");
            if (manufacturerBiosLabel != null) text(manufacturerBiosLabel, 255, "manufacturer BIOS label");
            if (supportStatus == null || biosRequirement == null) throw new IllegalArgumentException("support and BIOS status are required");
            if (biosRequirement == BiosRequirement.VERSION && (minimumBiosVersion == null || manufacturerBiosLabel == null)) {
                throw new IllegalArgumentException("VERSION requires a manufacturer BIOS version and label");
            }
            if (biosRequirement == BiosRequirement.VERSION && ("ALL".equalsIgnoreCase(minimumBiosVersion)
                    || "Latest Beta BIOS".equalsIgnoreCase(minimumBiosVersion)
                    || !minimumBiosVersion.equals(manufacturerBiosLabel.endsWith(".zip")
                        ? manufacturerBiosLabel.substring(0, manufacturerBiosLabel.length() - 4) : manufacturerBiosLabel))) {
                throw new IllegalArgumentException("minimum BIOS must retain the manufacturer's version, removing only a .zip suffix");
            }
            if (biosRequirement != BiosRequirement.VERSION && minimumBiosVersion != null) {
                throw new IllegalArgumentException("ALL/UNKNOWN must not fabricate a minimum BIOS version");
            }
            if (biosRequirement == BiosRequirement.ALL && !"ALL".equalsIgnoreCase(manufacturerBiosLabel)) {
                throw new IllegalArgumentException("ALL requires the manufacturer's literal All label");
            }
            if (supportStatus == SupportStatus.UNVERIFIED && (biosRequirement != BiosRequirement.UNKNOWN
                    || manufacturerBiosLabel != null || cpuStepping != null)) {
                throw new IllegalArgumentException("unverified support cannot claim a BIOS or CPU stepping");
            }
            officialUrl(sourceUrl);
        }
    }

    static void text(String value, int length, String field) {
        if (value == null || value.isBlank() || value.length() > length || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " must be nonblank and at most " + length + " characters");
        }
    }

    static void officialUrl(String url) {
        text(url, 2048, "source URL");
        var uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getHost() == null
                || !Set.of("www.msi.com", "www.asus.com", "www.asrock.com", "www.gigabyte.com").contains(uri.getHost())) {
            throw new IllegalArgumentException("source URL must be an official motherboard manufacturer's HTTPS URL");
        }
    }
}
