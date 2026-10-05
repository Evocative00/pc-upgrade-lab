package com.pcupgradelab.catalog.support;

import com.pcupgradelab.catalog.CatalogDtos;
import com.pcupgradelab.catalog.CatalogProduct;
import com.pcupgradelab.catalog.CatalogProductRepository;
import com.pcupgradelab.catalog.CatalogProductSourceRepository;
import com.pcupgradelab.common.ApiException;
import com.pcupgradelab.pc.PartType;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MotherboardCpuQueryService {
    private final CatalogProductRepository products;
    private final CatalogProductSourceRepository sources;
    private final MotherboardCpuSupportRepository support;
    public MotherboardCpuQueryService(CatalogProductRepository products, CatalogProductSourceRepository sources,
                                      MotherboardCpuSupportRepository support) {
        this.products = products; this.sources = sources; this.support = support;
    }

    public View find(String motherboardId, String cpuId) {
        requireType(motherboardId, PartType.MOTHERBOARD);
        if (cpuId != null) requireType(cpuId, PartType.CPU);
        var stored = support.findByMotherboardId(motherboardId);
        var profiles = stored.stream().map(actual -> {
            var profile = actual.support();
            var entries = profile.entries().stream().filter(entry -> cpuId == null || entry.cpuProductId().equals(cpuId))
                    .map(entry -> new Entry(products.findById(entry.cpuProductId()).orElseThrow().getModelName(), entry)).toList();
            var source = sources.findById(actual.evidenceSourceId()).orElseThrow();
            return new Profile(profile.revisionKey(), profile.revisionScope(), profile.hardwareRevision(), profile.conditions(), entries,
                    new CatalogDtos.Source(source.getSourceName(), source.getExternalId(), source.getSourceRevision(),
                            source.getSourceUrl(), source.getRetrievedAt()));
        }).toList();
        boolean hasEntries = profiles.stream().anyMatch(profile -> !profile.entries().isEmpty());
        return new View(motherboardId, cpuId, !stored.isEmpty(), false, hasEntries,
                "현재 카탈로그의 CPU만 발췌한 자료입니다. 기록 없음/UNVERIFIED는 미지원 판정이 아니며, 보드 리비전·CPU 스테핑·현재 BIOS를 확인해야 합니다.", profiles);
    }

    private CatalogProduct requireType(String id, PartType type) {
        var product = products.findById(id).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "CATALOG_PRODUCT_NOT_FOUND", "부품을 찾을 수 없습니다."));
        if (product.getType() != type) throw new ApiException(HttpStatus.BAD_REQUEST, "MOTHERBOARD_CPU_REQUIRES_CORRECT_TYPE",
                type == PartType.MOTHERBOARD ? "메인보드의 CPU 지원 정보만 조회할 수 있습니다." : "cpuProductId에는 CPU를 지정해야 합니다.");
        return product;
    }

    /** 내부 출처 ID/raw payload와 호환 판정은 공개하지 않는다. CPU별 최소 BIOS를 하나로 합치지 않는다. */
    public record View(String motherboardProductId, String requestedCpuProductId, boolean dataAvailable,
                       boolean listComplete, boolean hasRecordedEntries, String notice, List<Profile> profiles) {
        public View { profiles = List.copyOf(profiles); }
    }
    public record Profile(String revisionKey, MotherboardCpuSupport.RevisionScope revisionScope, String hardwareRevision,
                          String conditions, List<Entry> entries, CatalogDtos.Source source) {
        public Profile { entries = List.copyOf(entries); }
    }
    public record Entry(String cpuModelName, MotherboardCpuSupport.Entry support) { }
}
