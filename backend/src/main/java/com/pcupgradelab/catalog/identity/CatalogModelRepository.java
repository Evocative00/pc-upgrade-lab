package com.pcupgradelab.catalog.identity;

import com.pcupgradelab.pc.PartType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CatalogModelRepository extends JpaRepository<CatalogModel, String> {
    Optional<CatalogModel> findByCanonicalId(String canonicalId);

    @Query("""
            select m from CatalogModel m where (:type is null or m.type = :type)
            and (lower(concat(concat(m.manufacturer, ' '), m.modelName)) like :pattern escape '!'
                or exists (select a.id from CatalogModelAlias a where a.model.id = m.id
                    and a.normalizedAlias like :pattern escape '!'))
            """)
    Page<CatalogModel> searchForReview(@Param("type") PartType type, @Param("pattern") String pattern, Pageable pageable);
}
