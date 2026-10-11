package com.pcupgradelab.catalog;

import com.pcupgradelab.catalog.price.CatalogPriceDatabaseTimeZone;
import com.pcupgradelab.catalog.storage.StorageSpecRepository;
import com.pcupgradelab.pc.PartType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 비UTC MySQL 세션을 모킹한다. 신규 UTC 적재 출처와 레거시 seed 출처의 서로 다른 저장 계약을 지킨다. */
class CatalogEntrySourceTimeZoneTests {
    private static final Instant REVIEWED_AT=Instant.parse("2026-10-11T06:30:11Z");

    @ParameterizedTest
    @EnumSource(value=PartType.class,names={"PSU","CASE","CPU","MOTHERBOARD","RAM"})
    void detailSourceTimeUsesItsOriginalInsertionContractAndConvertsViewsBeforeLeavingTheScope(PartType type) {
        boolean utcInserted=type==PartType.PSU || type==PartType.CASE;
        var productService=mock(CatalogProductService.class); var cpus=mock(CpuSpecRepository.class);
        var boards=mock(MotherboardSpecRepository.class); var memory=mock(RamSpecRepository.class);
        var psus=mock(PsuSpecRepository.class); var cases=mock(CaseSpecRepository.class);
        var sources=mock(CatalogProductSourceRepository.class); var timeZone=mock(CatalogPriceDatabaseTimeZone.class);
        var service=new CatalogEntryService(productService,mock(CatalogProductRepository.class),cpus,boards,memory,
                mock(GpuSpecRepository.class),mock(MonitorSpecRepository.class),mock(StorageSpecRepository.class),psus,cases,sources,timeZone);
        String id="source-time-"+type;
        var product=new CatalogProductView(id,type,"Manufacturer","Exact source fixture",null,
                CatalogVerificationStatus.UNVERIFIED,false,REVIEWED_AT,REVIEWED_AT,
                new CatalogProductView.ReferencePrice(null,CatalogPriceStatus.UNCONFIRMED,REVIEWED_AT));
        when(productService.findById(id)).thenReturn(Optional.of(product));
        CatalogSpecification specification;
        switch(type){
            case PSU -> {
                specification=new CatalogSpecification.Psu(null,null,null,null,null,null,false,List.of());
                var entity=mock(PsuSpec.class);when(entity.toSpecification()).thenReturn((CatalogSpecification.Psu)specification);when(psus.findById(id)).thenReturn(Optional.of(entity));
            }
            case CASE -> {
                specification=new CatalogSpecification.Case(false,List.of(),false,List.of(),null,null,null,null,null,null,null,false,false);
                var entity=mock(CaseSpec.class);when(entity.toSpecification()).thenReturn((CatalogSpecification.Case)specification);when(cases.findById(id)).thenReturn(Optional.of(entity));
            }
            case CPU -> {
                specification=new CatalogSpecification.Cpu(null,null,null,null,null,null,null,null);
                var entity=mock(CpuSpec.class);when(entity.toSpecification()).thenReturn((CatalogSpecification.Cpu)specification);when(cpus.findById(id)).thenReturn(Optional.of(entity));
            }
            case MOTHERBOARD -> {
                specification=new CatalogSpecification.Motherboard(null,null,null,null,null,null,null,null);
                var entity=mock(MotherboardSpec.class);when(entity.toSpecification()).thenReturn((CatalogSpecification.Motherboard)specification);when(boards.findById(id)).thenReturn(Optional.of(entity));
            }
            case RAM -> {
                specification=new CatalogSpecification.Ram(null,null,null,null,null,null,null,null,null,null);
                var entity=mock(RamSpec.class);when(entity.toSpecification()).thenReturn((CatalogSpecification.Ram)specification);when(memory.findById(id)).thenReturn(Optional.of(entity));
            }
            default -> throw new IllegalArgumentException("Unexpected source fixture type");
        }
        var inUtc=new AtomicBoolean(false);var queryScopes=new ArrayList<Boolean>();var viewScopes=new ArrayList<Boolean>();
        doAnswer(invocation->{boolean original=inUtc.getAndSet(true);try{return invocation.<Supplier<?>>getArgument(0).get();}finally{inUtc.set(original);}}).when(timeZone).read(any());
        when(sources.findAllByProduct_IdOrderByIdAsc(id)).thenAnswer(invocation->{
            queryScopes.add(inUtc.get());
            // TIMESTAMP UTC evidence shifts +9h when read in the original +09 session with Hibernate's UTC binding.
            // Legacy seeds sent UTC wall-clock fields to that +09 session, so moving their read to UTC shifts -9h.
            Instant readAt=utcInserted ? (inUtc.get()?REVIEWED_AT:REVIEWED_AT.plusSeconds(9*3600))
                    : (inUtc.get()?REVIEWED_AT.minusSeconds(9*3600):REVIEWED_AT);
            var source=mock(CatalogProductSource.class);
            when(source.toView()).thenAnswer(call->{viewScopes.add(inUtc.get());return new CatalogSourceView(1L,
                    CatalogSourceName.MANUFACTURER,"exact-source",null,"https://example.invalid/specification",null,readAt);});
            return List.of(source);
        });
        var entry=service.findById(id).orElseThrow();
        assertThat(entry.specification()).isEqualTo(specification);
        assertThat(entry.sources()).hasSize(1);assertThat(entry.sources().getFirst().retrievedAt()).isEqualTo(REVIEWED_AT);
        assertThat(queryScopes).containsExactly(utcInserted);assertThat(viewScopes).containsExactly(utcInserted);
        assertThat(inUtc.get()).as("Caller session is restored after source query and DTO conversion").isFalse();
        if(utcInserted)verify(timeZone).read(any());else verifyNoInteractions(timeZone);
        verify(timeZone,never()).write(any());
    }
}
