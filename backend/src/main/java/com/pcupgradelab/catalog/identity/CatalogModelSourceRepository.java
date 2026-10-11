package com.pcupgradelab.catalog.identity;

import com.pcupgradelab.catalog.CatalogSourceName;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogModelSourceRepository extends JpaRepository<CatalogModelSource, Long> {
    List<CatalogModelSource> findAllByModel_IdOrderByIdAsc(String modelId);
    Optional<CatalogModelSource> findBySourceNameAndExternalId(CatalogSourceName sourceName, String externalId);
}
