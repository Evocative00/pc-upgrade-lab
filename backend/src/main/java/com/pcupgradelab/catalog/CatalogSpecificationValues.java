package com.pcupgradelab.catalog;

import com.pcupgradelab.pc.PartType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;

/** 제원 입력의 공통 검사. 미확인 값은 NULL로 보존하고 단위를 추정하거나 수치를 반올림하지 않는다. */
final class CatalogSpecificationValues {
    private static final Set<String> BUFFER_TYPES = Set.of("UNBUFFERED", "REGISTERED", "LOAD_REDUCED");
    private static final Set<String> GPU_CHIP_VENDORS = Set.of("NVIDIA", "AMD", "INTEL");
    private static final Set<Integer> PCIE_LANES = Set.of(1, 2, 4, 8, 16, 32);
    private static final Set<String> PSU_REQUIREMENT_BASES = Set.of("MINIMUM", "RECOMMENDED");
    private static final Set<String> GPU_CONNECTOR_TYPES = Set.of("PCIE_6PIN", "PCIE_8PIN",
            "PCIE_16PIN_UNSPECIFIED", "PCIE_12VHPWR", "PCIE_12V_2X6");
    private static final Set<String> MONITOR_PANEL_TYPES = Set.of("IPS", "VA", "TN", "OLED");
    private static final Set<String> MONITOR_ACTIVE_POWER_BASES = Set.of("MANUFACTURER_TYPICAL", "MEASURED_ACTIVE");

    private CatalogSpecificationValues() { }

    static String text(String value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return normalized;
    }

    static String code(String value, String field, int maxLength) {
        return text(value == null ? null : value.strip().replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT), field, maxLength);
    }

    static String compactCode(String value, String field, int maxLength) {
        // LGA 1700과 LGA1700처럼 표기 공백만 다른 소켓·DDR 코드를 통일한다.
        return text(value == null ? null : value.strip().replaceAll("\\s+", "")
                .toUpperCase(Locale.ROOT), field, maxLength);
    }

    static String motherboardFormFactor(String value) {
        String normalized = code(value, "formFactor", 32);
        if (normalized == null) {
            return null;
        }
        return switch (normalized) {
            case "MICRO ATX", "MICRO-ATX", "MICROATX" -> "MICRO_ATX";
            case "MINI ITX", "MINI-ITX", "MINIITX" -> "MINI_ITX";
            default -> normalized;
        };
    }

    static String memoryFormFactor(String value, String field) {
        String normalized = code(value, field, 16);
        if (normalized == null) {
            return null;
        }
        // 메인보드와 RAM이 같은 모듈 규격을 같은 코드로 비교할 수 있게 한다.
        return switch (normalized) {
            case "SO-DIMM", "SO DIMM" -> "SODIMM";
            default -> normalized;
        };
    }

    static String bufferType(String value) {
        String normalized = code(value, "bufferType", 16);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.replace(' ', '_').replace('-', '_');
        if (!BUFFER_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("bufferType must be UNBUFFERED, REGISTERED or LOAD_REDUCED");
        }
        return normalized;
    }

    static Integer positiveInt(Integer value, String field) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    static Integer positiveSmallInt(Integer value, String field) {
        positiveInt(value, field);
        if (value != null && value > Short.MAX_VALUE) {
            throw new IllegalArgumentException(field + " must be at most " + Short.MAX_VALUE);
        }
        return value;
    }

    static Long positiveLong(Long value, String field) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    static BigDecimal positiveDecimal(BigDecimal value, String field, int precision, int scale) {
        if (value == null) {
            return null;
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        BigDecimal normalized;
        try {
            // 소수점 이하의 0만 정리할 수 있다. 실제 수치가 달라지는 반올림은 거절한다.
            normalized = value.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(field + " must fit DECIMAL(" + precision + "," + scale + ")", ex);
        }
        if (normalized.precision() > precision) {
            throw new IllegalArgumentException(field + " must fit DECIMAL(" + precision + "," + scale + ")");
        }
        return normalized;
    }

    static Short toShort(Integer value) {
        return value == null ? null : value.shortValue();
    }

    static Integer toInteger(Short value) {
        return value == null ? null : value.intValue();
    }

    static String gpuChipVendor(String value) {
        return allowedCode(value, "chipVendor", 16, GPU_CHIP_VENDORS);
    }

    static String monitorPanelType(String value) {
        // Nano IPS 등 추가 기술명과 백라이트 종류는 출처에 보존하고 기본 패널 코드와 섞지 않는다.
        return allowedCode(value, "panelType", 16, MONITOR_PANEL_TYPES);
    }

    static String monitorActivePowerBasis(String value) {
        return allowedCode(value, "activePowerBasis", 64, MONITOR_ACTIVE_POWER_BASES);
    }

    static String psuRequirementBasis(String value) {
        return allowedCode(value, "psuRequirementBasis", 16, PSU_REQUIREMENT_BASES);
    }

    static String gpuConnectorType(String value) {
        String normalized = allowedCode(value, "connectorType", 32, GPU_CONNECTOR_TYPES);
        if (normalized == null) {
            throw new IllegalArgumentException("connectorType is required");
        }
        return normalized;
    }

    private static String allowedCode(String value, String field, int maxLength, Set<String> allowed) {
        String normalized = code(value, field, maxLength);
        if (normalized != null && !allowed.contains(normalized)) {
            throw new IllegalArgumentException(field + " has an unsupported value: " + normalized);
        }
        return normalized;
    }

    static String pcieVersion(String value) {
        String normalized = text(value, "pcieVersion", 16);
        if (normalized != null && !normalized.matches("[1-9][0-9]?\\.[0-9]")) {
            throw new IllegalArgumentException("pcieVersion must be a version such as 4.0, without lane information");
        }
        return normalized;
    }

    static Integer pcieLanes(Integer value, String field) {
        if (value != null && !PCIE_LANES.contains(value)) {
            throw new IllegalArgumentException(field + " must be 1, 2, 4, 8, 16 or 32");
        }
        return value;
    }

    static void requirePair(Object value, Object basis, String fields) {
        if ((value == null) != (basis == null)) {
            throw new IllegalArgumentException(fields + " must both be known or both be null");
        }
    }

    static CatalogProduct productOfType(CatalogProduct product, PartType expectedType) {
        if (product == null || product.getType() != expectedType) {
            throw new IllegalArgumentException("product must have type " + expectedType);
        }
        return product;
    }
}
