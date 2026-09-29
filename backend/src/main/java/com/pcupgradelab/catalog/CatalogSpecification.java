package com.pcupgradelab.catalog;

import com.pcupgradelab.pc.PartType;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** 이미 단위가 정리된 제원 입력·조회 값. 각 레코드가 부품 종류와 입력 검증을 함께 책임진다. */
public sealed interface CatalogSpecification permits CatalogSpecification.Cpu,
        CatalogSpecification.Motherboard, CatalogSpecification.Ram, CatalogSpecification.Gpu,
        CatalogSpecification.Monitor {
    PartType type();

    record Cpu(String socketCode, Integer coreCount, Integer threadCount,
               Integer baseClockMhz, Integer boostClockMhz, BigDecimal tdpW,
               Boolean hasIntegratedGraphics, String integratedGraphicsModel) implements CatalogSpecification {
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
