package com.pcupgradelab.catalog;

import com.pcupgradelab.pc.PartType;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 이미 단위가 정리된 제원 입력·조회 값. 각 레코드가 부품 종류와 입력 검증을 함께 책임진다. */
public sealed interface CatalogSpecification permits CatalogSpecification.Cpu,
        CatalogSpecification.Motherboard, CatalogSpecification.Ram, CatalogSpecification.Gpu,
        CatalogSpecification.Monitor, CatalogSpecification.Storage {
    PartType type();

    /**
     * TDP, Intel PBP, Intel MTP는 별도 공표값이며 실측 소비전력이 아니다.
     * coreCount/threadCount는 CPU 전체 수치다. P/E 개수는 NULL=미확인, 0=없음 확인이다.
     * baseClockMhz는 단일 코어 유형의 기본 클럭이며, P/E가 함께 있으면 각 기본 클럭만 쓴다.
     * boostClockMhz는 제조사 공표 CPU 최대 부스트로, 모든 코어의 동시 동작 클럭이 아니다.
     */
    record Cpu(String socketCode, Integer coreCount, Integer threadCount,
               Integer baseClockMhz, Integer boostClockMhz, BigDecimal tdpW,
               Boolean hasIntegratedGraphics, String integratedGraphicsModel,
               BigDecimal processorBasePowerW, BigDecimal maximumTurboPowerW,
               Integer performanceCoreCount, Integer efficientCoreCount,
               Integer performanceCoreBaseClockMhz, Integer efficientCoreBaseClockMhz,
               Integer performanceCoreBoostClockMhz, Integer efficientCoreBoostClockMhz)
            implements CatalogSpecification {
        /** 기존 Java 호출부와 8항목 제원은 추가 정보를 추정하지 않고 계속 사용한다. */
        public Cpu(String socketCode, Integer coreCount, Integer threadCount,
                   Integer baseClockMhz, Integer boostClockMhz, BigDecimal tdpW,
                   Boolean hasIntegratedGraphics, String integratedGraphicsModel) {
            this(socketCode, coreCount, threadCount, baseClockMhz, boostClockMhz,
                    tdpW, hasIntegratedGraphics, integratedGraphicsModel,
                    null, null, null, null, null, null, null, null);
        }

        public Cpu {
            socketCode = CatalogSpecificationValues.compactCode(socketCode, "socketCode", 32);
            coreCount = CatalogSpecificationValues.positiveSmallInt(coreCount, "coreCount");
            threadCount = CatalogSpecificationValues.positiveSmallInt(threadCount, "threadCount");
            baseClockMhz = CatalogSpecificationValues.positiveInt(baseClockMhz, "baseClockMhz");
            boostClockMhz = CatalogSpecificationValues.positiveInt(boostClockMhz, "boostClockMhz");
            tdpW = CatalogSpecificationValues.positiveDecimal(tdpW, "tdpW", 8, 2);
            integratedGraphicsModel = CatalogSpecificationValues.text(integratedGraphicsModel,
                    "integratedGraphicsModel", 128);
            if (Boolean.FALSE.equals(hasIntegratedGraphics) && integratedGraphicsModel != null) {
                throw new IllegalArgumentException("integratedGraphicsModel must be null when integrated graphics is absent");
            }
            processorBasePowerW = CatalogSpecificationValues.positiveDecimal(processorBasePowerW,
                    "processorBasePowerW", 8, 2);
            maximumTurboPowerW = CatalogSpecificationValues.positiveDecimal(maximumTurboPowerW,
                    "maximumTurboPowerW", 8, 2);
            if (processorBasePowerW != null && maximumTurboPowerW != null
                    && maximumTurboPowerW.compareTo(processorBasePowerW) < 0) {
                throw new IllegalArgumentException("maximumTurboPowerW must not be less than processorBasePowerW");
            }
            performanceCoreCount = CatalogSpecificationValues.nonNegativeSmallInt(performanceCoreCount,
                    "performanceCoreCount");
            efficientCoreCount = CatalogSpecificationValues.nonNegativeSmallInt(efficientCoreCount,
                    "efficientCoreCount");
            if (coreCount != null && ((performanceCoreCount != null && performanceCoreCount > coreCount)
                    || (efficientCoreCount != null && efficientCoreCount > coreCount))) {
                throw new IllegalArgumentException("A P/E core count must not exceed coreCount");
            }
            if (performanceCoreCount != null && efficientCoreCount != null) {
                int total = performanceCoreCount + efficientCoreCount;
                if (total == 0 || (coreCount != null && coreCount != total)) {
                    throw new IllegalArgumentException("Known P/E core counts must have a positive sum matching coreCount");
                }
                if (performanceCoreCount > 0 && efficientCoreCount > 0 && baseClockMhz != null) {
                    throw new IllegalArgumentException("A hybrid CPU must use separate P/E base clocks");
                }
            }
            performanceCoreBaseClockMhz = CatalogSpecificationValues.positiveInt(performanceCoreBaseClockMhz,
                    "performanceCoreBaseClockMhz");
            efficientCoreBaseClockMhz = CatalogSpecificationValues.positiveInt(efficientCoreBaseClockMhz,
                    "efficientCoreBaseClockMhz");
            performanceCoreBoostClockMhz = CatalogSpecificationValues.positiveInt(performanceCoreBoostClockMhz,
                    "performanceCoreBoostClockMhz");
            efficientCoreBoostClockMhz = CatalogSpecificationValues.positiveInt(efficientCoreBoostClockMhz,
                    "efficientCoreBoostClockMhz");
            if (boostClockMhz != null && ((performanceCoreBoostClockMhz != null
                    && performanceCoreBoostClockMhz > boostClockMhz) || (efficientCoreBoostClockMhz != null
                    && efficientCoreBoostClockMhz > boostClockMhz))) {
                throw new IllegalArgumentException("A P/E boost clock must not exceed the CPU maximum boost clock");
            }
            validateCoreClocks(performanceCoreCount, performanceCoreBaseClockMhz,
                    performanceCoreBoostClockMhz, "P");
            validateCoreClocks(efficientCoreCount, efficientCoreBaseClockMhz,
                    efficientCoreBoostClockMhz, "E");
        }

        private static void validateCoreClocks(Integer count, Integer base, Integer boost, String kind) {
            if (Integer.valueOf(0).equals(count) && (base != null || boost != null)) {
                throw new IllegalArgumentException(kind + " core clocks must be null when that core type is absent");
            }
            if (base != null && boost != null && boost < base) {
                throw new IllegalArgumentException(kind + " core boost clock must not be less than its base clock");
            }
        }

        @Override
        public PartType type() { return PartType.CPU; }
    }

    record Motherboard(String socketCode, String chipset, String formFactor,
                       String memoryType, String memoryFormFactor, Integer memorySlotCount,
                       Long maxMemoryBytes, Boolean supportsEcc) implements CatalogSpecification {
        public Motherboard {
            socketCode = CatalogSpecificationValues.compactCode(socketCode, "socketCode", 32);
            chipset = CatalogSpecificationValues.text(chipset, "chipset", 64);
            formFactor = CatalogSpecificationValues.motherboardFormFactor(formFactor);
            memoryType = CatalogSpecificationValues.compactCode(memoryType, "memoryType", 10);
            memoryFormFactor = CatalogSpecificationValues.memoryFormFactor(memoryFormFactor, "memoryFormFactor");
            memorySlotCount = CatalogSpecificationValues.positiveSmallInt(memorySlotCount, "memorySlotCount");
            maxMemoryBytes = CatalogSpecificationValues.positiveLong(maxMemoryBytes, "maxMemoryBytes");
        }

        @Override
        public PartType type() { return PartType.MOTHERBOARD; }
    }

    record Ram(String memoryType, Long moduleCapacityBytes, Integer moduleCount,
               Integer dataRateMts, String moduleFormFactor, Integer pinCount,
               Boolean isEcc, String bufferType, BigDecimal voltageV,
               BigDecimal heightMm) implements CatalogSpecification {
        public Ram {
            memoryType = CatalogSpecificationValues.compactCode(memoryType, "memoryType", 10);
            moduleCapacityBytes = CatalogSpecificationValues.positiveLong(moduleCapacityBytes, "moduleCapacityBytes");
            moduleCount = CatalogSpecificationValues.positiveSmallInt(moduleCount, "moduleCount");
            dataRateMts = CatalogSpecificationValues.positiveInt(dataRateMts, "dataRateMts");
            moduleFormFactor = CatalogSpecificationValues.memoryFormFactor(moduleFormFactor, "moduleFormFactor");
            pinCount = CatalogSpecificationValues.positiveSmallInt(pinCount, "pinCount");
            bufferType = CatalogSpecificationValues.bufferType(bufferType);
            voltageV = CatalogSpecificationValues.positiveDecimal(voltageV, "voltageV", 5, 3);
            heightMm = CatalogSpecificationValues.positiveDecimal(heightMm, "heightMm", 7, 2);
        }

        @Override
        public PartType type() { return PartType.RAM; }
    }

    /**
     * 데스크톱 외장 그래픽카드 한 제품의 제원. 카드 제조사는 공용 제품에 저장한다.
     * 물리 단자 크기/실제 레인 수, 카드 전력/시스템 파워 요구량은 서로 다른 값이다.
     */
    record Gpu(String chipVendor, String chipset, Long vramBytes, String memoryType,
               String pcieVersion, Integer pcieConnectorLanes, Integer pcieActiveLanes,
               BigDecimal lengthMm, BigDecimal heightMm, BigDecimal thicknessMm,
               BigDecimal slotWidth, BigDecimal cardPowerW, String cardPowerBasis,
               Integer psuRequirementW, String psuRequirementBasis, boolean powerConnectorsKnown,
               List<GpuPowerConnector> powerConnectors) implements CatalogSpecification {
        public Gpu {
            chipVendor = CatalogSpecificationValues.gpuChipVendor(chipVendor);
            chipset = CatalogSpecificationValues.text(chipset, "chipset", 128);
            vramBytes = CatalogSpecificationValues.positiveLong(vramBytes, "vramBytes");
            memoryType = CatalogSpecificationValues.compactCode(memoryType, "memoryType", 16);
            pcieVersion = CatalogSpecificationValues.pcieVersion(pcieVersion);
            pcieConnectorLanes = CatalogSpecificationValues.pcieLanes(pcieConnectorLanes, "pcieConnectorLanes");
            pcieActiveLanes = CatalogSpecificationValues.pcieLanes(pcieActiveLanes, "pcieActiveLanes");
            if (pcieConnectorLanes != null && pcieActiveLanes != null && pcieActiveLanes > pcieConnectorLanes) {
                throw new IllegalArgumentException("pcieActiveLanes must not exceed pcieConnectorLanes");
            }
            lengthMm = CatalogSpecificationValues.positiveDecimal(lengthMm, "lengthMm", 8, 2);
            heightMm = CatalogSpecificationValues.positiveDecimal(heightMm, "heightMm", 8, 2);
            thicknessMm = CatalogSpecificationValues.positiveDecimal(thicknessMm, "thicknessMm", 8, 2);
            // 두께에서 슬롯 수를 계산하지 않고 제조사가 공표한 값을 따로 보존한다.
            slotWidth = CatalogSpecificationValues.positiveDecimal(slotWidth, "slotWidth", 4, 2);
            cardPowerW = CatalogSpecificationValues.positiveDecimal(cardPowerW, "cardPowerW", 8, 2);
            cardPowerBasis = CatalogSpecificationValues.code(cardPowerBasis, "cardPowerBasis", 64);
            CatalogSpecificationValues.requirePair(cardPowerW, cardPowerBasis, "cardPowerW/cardPowerBasis");
            psuRequirementW = CatalogSpecificationValues.positiveInt(psuRequirementW, "psuRequirementW");
            psuRequirementBasis = CatalogSpecificationValues.psuRequirementBasis(psuRequirementBasis);
            CatalogSpecificationValues.requirePair(psuRequirementW, psuRequirementBasis,
                    "psuRequirementW/psuRequirementBasis");
            if (powerConnectors == null || powerConnectors.stream().anyMatch(value -> value == null)) {
                throw new IllegalArgumentException("powerConnectors must be a non-null list without null items");
            }
            if (!powerConnectorsKnown && !powerConnectors.isEmpty()) {
                throw new IllegalArgumentException("Unknown power connectors must have an empty list");
            }
            var types = new HashSet<String>();
            for (GpuPowerConnector connector : powerConnectors) {
                if (!types.add(connector.connectorType())) {
                    throw new IllegalArgumentException("A power connector type occurs more than once");
                }
            }
            // DB의 행 순서에 관계없이 비교할 수 있게 정렬한다. 반환 목록은 변경할 수 없다.
            powerConnectors = powerConnectors.stream()
                    .sorted(Comparator.comparing(GpuPowerConnector::connectorType)).toList();
        }

        @Override
        public PartType type() { return PartType.GPU; }
    }

    /**
     * 데스크톱 외부 모니터 한 제품의 제원. 주사율은 기본 해상도에서 지원하는 최대값이다.
     * 사용자 PC의 현재 출력 설정이나 목표 게임 FPS는 여기에 저장하지 않는다.
     */
    record Monitor(BigDecimal screenSizeInches, Integer nativeWidthPx, Integer nativeHeightPx,
                   String panelType, BigDecimal nativeStandardRefreshHz, Boolean hasRefreshOverclock,
                   BigDecimal nativeOcRefreshHz, BigDecimal activePowerW, String activePowerBasis,
                   String activePowerConditions) implements CatalogSpecification {
        public Monitor {
            screenSizeInches = CatalogSpecificationValues.positiveDecimal(screenSizeInches,
                    "screenSizeInches", 5, 2);
            nativeWidthPx = CatalogSpecificationValues.positiveInt(nativeWidthPx, "nativeWidthPx");
            nativeHeightPx = CatalogSpecificationValues.positiveInt(nativeHeightPx, "nativeHeightPx");
            CatalogSpecificationValues.requirePair(nativeWidthPx, nativeHeightPx,
                    "nativeWidthPx/nativeHeightPx");
            panelType = CatalogSpecificationValues.monitorPanelType(panelType);
            // 비 OC 최대값과 OC 최대값을 분리한다. 소수 주사율을 정수로 반올림하지 않는다.
            nativeStandardRefreshHz = CatalogSpecificationValues.positiveDecimal(nativeStandardRefreshHz,
                    "nativeStandardRefreshHz", 7, 3);
            nativeOcRefreshHz = CatalogSpecificationValues.positiveDecimal(nativeOcRefreshHz,
                    "nativeOcRefreshHz", 7, 3);
            if (nativeOcRefreshHz != null && !Boolean.TRUE.equals(hasRefreshOverclock)) {
                throw new IllegalArgumentException("nativeOcRefreshHz requires confirmed refresh overclock support");
            }
            if (nativeStandardRefreshHz != null && nativeOcRefreshHz != null
                    && nativeOcRefreshHz.compareTo(nativeStandardRefreshHz) <= 0) {
                throw new IllegalArgumentException("nativeOcRefreshHz must exceed nativeStandardRefreshHz");
            }
            // NULL=미확인, FALSE=미지원 확인. TRUE이지만 최대 OC 값은 아직 모를 수 있다.
            // 전력은 사용 중 값만 허용하며 최대·대기 전력이나 어댑터 정격을 넣지 않는다.
            activePowerW = CatalogSpecificationValues.positiveDecimal(activePowerW, "activePowerW", 8, 2);
            activePowerBasis = CatalogSpecificationValues.monitorActivePowerBasis(activePowerBasis);
            activePowerConditions = CatalogSpecificationValues.text(activePowerConditions,
                    "activePowerConditions", 1000);
            CatalogSpecificationValues.requirePair(activePowerW, activePowerBasis,
                    "activePowerW/activePowerBasis");
            CatalogSpecificationValues.requirePair(activePowerW, activePowerConditions,
                    "activePowerW/activePowerConditions");
        }

        @Override
        public PartType type() { return PartType.MONITOR; }
    }

    /** 저장장치 한 개의 제조사 명목 제원. 스캔한 실제 장치/파일시스템 용량과 구분한다. */
    record Storage(String storageKind, Long capacityBytes, BigDecimal advertisedCapacityGb,
                   String capacityBasis, String formFactor, String busInterface,
                   String interfaceProtocol, String pcieVersion, Integer pcieLanes,
                   String nvmeVersion, String sataVersion, String connectorKey,
                   String m2LengthCode, BigDecimal lengthMm, BigDecimal widthMm,
                   BigDecimal heightMm, String dimensionsBasis, Boolean heatsinkIncluded)
            implements CatalogSpecification {
        public Storage {
            storageKind = allowed(storageKind, "storageKind", Set.of("SSD", "HDD"));
            capacityBytes = CatalogSpecificationValues.positiveLong(capacityBytes, "capacityBytes");
            advertisedCapacityGb = CatalogSpecificationValues.positiveDecimal(advertisedCapacityGb,
                    "advertisedCapacityGb", 12, 3);
            capacityBasis = allowed(capacityBasis, "capacityBasis", Set.of("DECIMAL_GB"));
            if ((capacityBytes != null || advertisedCapacityGb != null) != (capacityBasis != null)) {
                throw new IllegalArgumentException("A known nominal capacity requires capacityBasis; unknown capacity has no basis");
            }
            if (capacityBytes != null && advertisedCapacityGb != null
                    && advertisedCapacityGb.multiply(new BigDecimal("1000000000"))
                    .compareTo(BigDecimal.valueOf(capacityBytes)) != 0) {
                throw new IllegalArgumentException("capacityBytes must match decimal advertisedCapacityGb");
            }
            formFactor = allowed(formFactor, "formFactor",
                    Set.of("TWO_POINT_FIVE_INCH", "THREE_POINT_FIVE_INCH", "M2"));
            busInterface = allowed(busInterface, "busInterface", Set.of("SATA", "PCIE"));
            interfaceProtocol = allowed(interfaceProtocol, "interfaceProtocol", Set.of("ATA", "NVME"));
            pcieVersion = CatalogSpecificationValues.pcieVersion(pcieVersion);
            pcieLanes = CatalogSpecificationValues.pcieLanes(pcieLanes, "pcieLanes");
            nvmeVersion = version(nvmeVersion, "nvmeVersion");
            sataVersion = version(sataVersion, "sataVersion");
            connectorKey = allowed(connectorKey, "connectorKey", Set.of("B", "M", "B_M"));
            m2LengthCode = allowed(m2LengthCode, "m2LengthCode",
                    Set.of("2230", "2242", "2260", "2280", "22110"));
            if ("SATA".equals(busInterface)
                    && (pcieVersion != null || pcieLanes != null || "NVME".equals(interfaceProtocol) || nvmeVersion != null)) {
                throw new IllegalArgumentException("SATA storage cannot have PCIe/NVMe specification values");
            }
            if ("PCIE".equals(busInterface) && (sataVersion != null || "ATA".equals(interfaceProtocol))) {
                throw new IllegalArgumentException("PCIe storage cannot have SATA/ATA specification values");
            }
            if ((pcieVersion != null || pcieLanes != null) && !"PCIE".equals(busInterface)) {
                throw new IllegalArgumentException("PCIe values require a confirmed PCIe bus");
            }
            if ("NVME".equals(interfaceProtocol) && !"PCIE".equals(busInterface)) {
                throw new IllegalArgumentException("NVMe requires a confirmed PCIe bus");
            }
            if (nvmeVersion != null && !"NVME".equals(interfaceProtocol)) {
                throw new IllegalArgumentException("nvmeVersion requires a confirmed NVMe protocol");
            }
            if (sataVersion != null && !"SATA".equals(busInterface)) {
                throw new IllegalArgumentException("sataVersion requires a confirmed SATA bus");
            }
            if ((connectorKey != null || m2LengthCode != null) && !"M2".equals(formFactor)) {
                throw new IllegalArgumentException("M.2 key/length code require a confirmed M2 form factor");
            }
            lengthMm = CatalogSpecificationValues.positiveDecimal(lengthMm, "lengthMm", 8, 2);
            widthMm = CatalogSpecificationValues.positiveDecimal(widthMm, "widthMm", 8, 2);
            heightMm = CatalogSpecificationValues.positiveDecimal(heightMm, "heightMm", 8, 2);
            dimensionsBasis = allowed(dimensionsBasis, "dimensionsBasis",
                    Set.of("NOMINAL", "MANUFACTURER_MAXIMUM", "PUBLISHED"));
            if ((lengthMm != null || widthMm != null || heightMm != null) != (dimensionsBasis != null)) {
                throw new IllegalArgumentException("Known dimensions require dimensionsBasis; unknown dimensions have no basis");
            }
            // M.2 2280 is a nominal form code, not an upper bound on published casing dimensions.
        }

        private static String allowed(String value, String field, Set<String> values) {
            String normalized = CatalogSpecificationValues.code(value, field, 32);
            if (normalized != null && !values.contains(normalized)) {
                throw new IllegalArgumentException(field + " has an unsupported value: " + normalized);
            }
            return normalized;
        }

        private static String version(String value, String field) {
            String normalized = CatalogSpecificationValues.text(value, field, 16);
            if (normalized != null && !normalized.matches("[1-9][0-9]?\\.[0-9]{1,2}[a-z]?")) {
                throw new IllegalArgumentException(field + " must be a published version such as 1.4b or 3.0");
            }
            return normalized;
        }

        @Override
        public PartType type() { return PartType.STORAGE; }
    }

    /** 카드 본체의 보조전원. 변환 어댑터 쪽 입력 단자 개수는 여기에 섞지 않는다. */
    record GpuPowerConnector(String connectorType, Integer connectorCount) {
        public GpuPowerConnector {
            connectorType = CatalogSpecificationValues.gpuConnectorType(connectorType);
            connectorCount = CatalogSpecificationValues.positiveSmallInt(connectorCount, "connectorCount");
            if (connectorCount == null) {
                throw new IllegalArgumentException("connectorCount is required");
            }
        }
    }
}
