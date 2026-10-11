package com.pcupgradelab.catalog.identity;

import com.pcupgradelab.catalog.CatalogProduct;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CatalogIdentityProductRepository extends JpaRepository<CatalogProduct, String> {
    /** 동일 제품의 승인 연결은 읽기·검증·저장 전체를 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CatalogProduct p where p.id = :productId")
    Optional<CatalogProduct> findForIdentityBinding(@Param("productId") String productId);

    Optional<CatalogProduct> findByCanonicalId(String canonicalId);
    List<CatalogProduct> findAllByModelIdOrderByModelNameAscIdAsc(String modelId);
}
