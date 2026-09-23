package com.pcupgradelab.pc;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PcConfigurationRepository extends JpaRepository<PcConfiguration, Long> {
    @EntityGraph(attributePaths = "parts")
    Optional<PcConfiguration> findByIdAndOwnerKey(Long id, String ownerKey);

    // List responses use summaries only; do not fetch-join a collection with pagination.
    Page<PcConfiguration> findAllByOwnerKey(String ownerKey, Pageable pageable);
}
