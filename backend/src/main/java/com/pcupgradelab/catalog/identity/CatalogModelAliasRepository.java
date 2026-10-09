package com.pcupgradelab.catalog.identity;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogModelAliasRepository extends JpaRepository<CatalogModelAlias, Long> {
    List<CatalogModelAlias> findAllByModel_IdOrderByIdAsc(String modelId);
}
