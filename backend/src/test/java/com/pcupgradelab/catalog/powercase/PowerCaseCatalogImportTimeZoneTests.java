package com.pcupgradelab.catalog.powercase;

import com.pcupgradelab.catalog.*;
import com.pcupgradelab.catalog.identity.*;
import com.pcupgradelab.catalog.price.*;
import com.pcupgradelab.catalog.shared.AllCatalogEvidenceTimestampReader;
import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** H2에서는 드러나지 않는 MySQL seed wall-clock 계약을 DB 없이 서비스 경계에서 검증한다. */
class PowerCaseCatalogImportTimeZoneTests {
    @Test void legacyEvidenceIsReadOutsideTheUtcScopeBeforeAnyWrite() {
        var plan = new PowerCaseCatalogImportLoader().loadForCheck(Path.of("..").toAbsolutePath().normalize());
        var first = plan.baseline().baseline().products().getFirst();
        assertThat(first.bindLegacy()).as("Pinned baseline's first product exercises the legacy insertion contract").isTrue();
        var products=mock(CatalogProductRepository.class); var sources=mock(CatalogProductSourceRepository.class);
        var entries=mock(CatalogEntryService.class); var timeZone=mock(CatalogPriceDatabaseTimeZone.class);
        var times=mock(AllCatalogEvidenceTimestampReader.class); var audit=mock(PowerCaseCatalogPreservationAudit.class);
        var canonical=mock(CatalogIdentityProductRepository.class);
        var service = new PowerCaseCatalogImportService(products,sources,entries,canonical,mock(CatalogModelRepository.class),
                mock(CatalogIdentityService.class),mock(CatalogReferencePriceRepository.class),mock(CatalogPriceImportService.class),
                mock(CatalogPriceMappingRepository.class),mock(CatalogPriceObservationRepository.class),timeZone,times,audit,mock(EntityManager.class));
        when(products.count()).thenReturn(308L); when(audit.capture()).thenReturn(Map.of());
        var product=mock(CatalogProduct.class); var identity=first.identity();
        when(product.getId()).thenReturn("legacy-product"); when(product.getType()).thenReturn(identity.type());
        when(product.getManufacturer()).thenReturn(identity.manufacturer()); when(product.getModelName()).thenReturn(identity.modelName());
        when(product.getPartNumber()).thenReturn(identity.partNumber()); when(product.getCanonicalId()).thenReturn(identity.canonicalId());
        when(product.getIdentityKind()).thenReturn(identity.identityKind()); when(product.getRole()).thenReturn(identity.role());
        when(product.getVerificationStatus()).thenReturn(identity.verificationStatus()); when(product.isActive()).thenReturn(identity.active());
        var source=mock(CatalogProductSource.class); when(source.getProduct()).thenReturn(product);
        when(sources.findBySourceNameAndExternalId(first.sourceIdentity().sourceName(),first.sourceIdentity().externalId())).thenReturn(Optional.of(source));
        when(sources.findBySourceNameAndExternalId(first.evidence().sourceName(),first.evidence().externalId())).thenReturn(Optional.of(source));
        when(entries.findById("legacy-product")).thenReturn(Optional.of(new CatalogEntryView(null,first.specification(),List.of())));
        var inUtc = new AtomicBoolean(false);
        doAnswer(invocation -> {
            boolean old=inUtc.getAndSet(true);
            try { return invocation.<Supplier<?>>getArgument(0).get(); } finally { inUtc.set(old); }
        }).when(timeZone).read(any());
        when(times.read(source,true)).thenAnswer(invocation -> {
            assertThat(inUtc.get()).as("MySQL legacy CAST must retain the original connection session").isFalse();
            throw new BaselineScopeVerified();
        });
        assertThatThrownBy(() -> service.preview(plan)).isInstanceOf(BaselineScopeVerified.class);
        verify(times).read(source,true);
        verify(timeZone,never()).write(any()); verify(entries,never()).create(any());
    }
    private static final class BaselineScopeVerified extends RuntimeException { }
}
