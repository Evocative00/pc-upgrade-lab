package com.pcupgradelab.catalog.storage;

import com.pcupgradelab.catalog.CatalogProductRepository;
import com.pcupgradelab.common.ApiException;
import com.pcupgradelab.pc.PartType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal explicit registration only; no startup loader or HTTP write endpoint. */
@Service
@Transactional(readOnly = true)
public class MotherboardStorageService {
    private final CatalogProductRepository products;
    private final MotherboardStorageProfileRepository profiles;
    public MotherboardStorageService(CatalogProductRepository products, MotherboardStorageProfileRepository profiles) {
        this.products = products; this.profiles = profiles;
    }
    @Transactional
    public void register(String motherboardId, MotherboardStorageSupport support) {
        if (support == null) throw new IllegalArgumentException("storage support is required");
        var board = products.findById(motherboardId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "CATALOG_PRODUCT_NOT_FOUND", "부품을 찾을 수 없습니다."));
        if (board.getType() != PartType.MOTHERBOARD) throw new ApiException(
                HttpStatus.BAD_REQUEST, "INVALID_INPUT", "메인보드의 저장장치 지원 자료만 등록할 수 있습니다.");
        if (profiles.existsByMotherboard_IdAndRevisionKey(motherboardId, support.revisionKey())) {
            throw new IllegalArgumentException("Storage support revision is already registered");
        }
        profiles.saveAndFlush(new MotherboardStorageProfile(board, support));
    }
}
