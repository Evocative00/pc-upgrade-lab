package com.pcupgradelab.catalog.identity;

import com.pcupgradelab.common.ApiException;
import com.pcupgradelab.pc.PartType;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CatalogModelQueryService {
    private final CatalogModelRepository models;
    private final CatalogModelSourceRepository sources;
    private final CatalogModelAliasRepository aliases;
    private final CatalogIdentityProductRepository products;

    public CatalogModelQueryService(CatalogModelRepository models, CatalogModelSourceRepository sources,
                                    CatalogModelAliasRepository aliases, CatalogIdentityProductRepository products) {
        this.models = models; this.sources = sources; this.aliases = aliases; this.products = products;
    }

    public CatalogIdentityDtos.PageResponse search(PartType type, String q, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE || q != null && q.length() > 100)
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "모델 검색 범위와 검색어를 확인해 주세요.");
        String keyword = q == null ? "" : q.strip().replace("®", "").replace("™", "")
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String pattern = "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var result = models.searchForReview(type, pattern, PageRequest.of(page, size, Sort.by("type", "manufacturer", "modelName", "id")));
        return new CatalogIdentityDtos.PageResponse(result.getContent().stream().map(CatalogIdentityDtos.Model::from).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    public CatalogIdentityDtos.Detail findById(String id) {
        if (id == null || id.isBlank() || id.length() > 128)
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "모델 ID를 확인해 주세요.");
        var model = models.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "CATALOG_MODEL_NOT_FOUND", "모델을 찾을 수 없습니다."));
        return new CatalogIdentityDtos.Detail(CatalogIdentityDtos.Model.from(model),
                aliases.findAllByModel_IdOrderByIdAsc(id).stream().map(CatalogIdentityDtos.Alias::from).toList(),
                sources.findAllByModel_IdOrderByIdAsc(id).stream().map(CatalogIdentityDtos.Source::from).toList(),
                products.findAllByModelIdOrderByModelNameAscIdAsc(id).stream().map(CatalogIdentityDtos.ProductCandidate::from).toList());
    }
}
