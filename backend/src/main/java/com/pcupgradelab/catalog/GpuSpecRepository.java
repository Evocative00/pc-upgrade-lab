package com.pcupgradelab.catalog;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 공용 제품 ID를 키로 사용하는 그래픽카드 제원 저장 공간. */
public interface GpuSpecRepository extends JpaRepository<GpuSpec, String> {
    @Query("""
            select distinct spec from GpuSpec spec left join fetch spec.powerConnectors
            where spec.productId in :productIds
            """)
    List<GpuSpec> findAllWithPowerConnectors(@Param("productIds") Collection<String> productIds);
}
