package com.pcupgradelab.catalog.storage;

import com.pcupgradelab.catalog.CatalogProductRepository;
import com.pcupgradelab.common.ApiException;
import com.pcupgradelab.pc.PartType;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MotherboardStorageQueryService {
    private final CatalogProductRepository products;
    private final MotherboardStorageProfileRepository profiles;
    public MotherboardStorageQueryService(CatalogProductRepository products, MotherboardStorageProfileRepository profiles) {
        this.products = products; this.profiles = profiles;
    }
    public View find(String motherboardId) {
        var board = products.findById(motherboardId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "CATALOG_PRODUCT_NOT_FOUND", "부품을 찾을 수 없습니다."));
        if (board.getType() != PartType.MOTHERBOARD) throw new ApiException(
                HttpStatus.BAD_REQUEST, "INVALID_INPUT", "메인보드 제품의 저장장치 지원 자료를 조회해야 합니다.");
        var values = profiles.findAllByMotherboard_IdOrderByRevisionKeyAsc(motherboardId).stream()
                .map(MotherboardStorageProfile::support).toList();
        var views = values.stream().map(value -> new Profile(value.revisionKey(), value.revisionScope(),
                value.hardwareRevision(), value.completeDataKnown(), value.conditions(), value.slots(), value.sources())).toList();
        return new View(motherboardId, !values.isEmpty(), !values.isEmpty()
                && values.stream().allMatch(MotherboardStorageSupport::completeDataKnown), views,
                "보드 리비전별 저장장치 지원 자료입니다. 빈 목록·미확인 값은 미지원 판정이 아니며 CPU·BIOS·공유 조건 확인이 필요합니다. 이 조회는 SSD 호환 판정을 수행하지 않습니다.");
    }
    public record View(String motherboardProductId, boolean dataAvailable, boolean completeDataKnown,
                       List<Profile> profiles, String note) {
        public View { profiles = List.copyOf(profiles); }
    }
    public record Profile(String revisionKey, MotherboardStorageSupport.RevisionScope revisionScope,
                          String hardwareRevision, boolean completeDataKnown, String conditions,
                          List<MotherboardStorageSupport.Slot> slots, List<MotherboardStorageSupport.Source> sources) {
        public Profile { slots = List.copyOf(slots); sources = List.copyOf(sources); }
    }
}
