package com.pcupgradelab.catalog.price;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogPriceMappingRepository extends JpaRepository<CatalogPriceMapping, Long> {
    Optional<CatalogPriceMapping> findBySourceNameAndExternalId(String sourceName, String externalId);
}
