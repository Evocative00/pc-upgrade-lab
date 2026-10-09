package com.pcupgradelab.catalog.storage;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MotherboardStorageProfileRepository extends JpaRepository<MotherboardStorageProfile, Long> {
    List<MotherboardStorageProfile> findAllByMotherboard_IdOrderByRevisionKeyAsc(String motherboardId);
    boolean existsByMotherboard_IdAndRevisionKey(String motherboardId, String revisionKey);
}
