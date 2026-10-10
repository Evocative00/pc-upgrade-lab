package com.pcupgradelab.catalog;

import com.pcupgradelab.catalog.storage.StorageSpec;
import com.pcupgradelab.catalog.storage.StorageSpecRepository;
import com.pcupgradelab.catalog.shared.SharedPriceAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 제품·미확정 가격·종류별 제원·출처를 하나의 트랜잭션으로 등록한다.
 * 신규 등록만 제공하며 이미 등록된 외부 원본을 자동 덮어쓰거나 활성화하지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class CatalogEntryService {
    private final CatalogProductService productService;
    private final CatalogProductRepository products;
    private final CpuSpecRepository cpus;
    private final MotherboardSpecRepository motherboards;
    private final RamSpecRepository ram;
    private final GpuSpecRepository gpus;
    private final MonitorSpecRepository monitors;
    private final StorageSpecRepository storage;
    private final CatalogProductSourceRepository sources;
    private final SharedPriceAdapter sharedPrices;

    public CatalogEntryService(CatalogProductService productService, CatalogProductRepository products,
                               CpuSpecRepository cpus, MotherboardSpecRepository motherboards,
                               RamSpecRepository ram, GpuSpecRepository gpus,
                               MonitorSpecRepository monitors, StorageSpecRepository storage,
                               CatalogProductSourceRepository sources) {
        this(productService, products, cpus, motherboards, ram, gpus, monitors, storage, sources,
                SharedPriceAdapter.disabled());
    }

    @Autowired
    public CatalogEntryService(CatalogProductService productService, CatalogProductRepository products,
                               CpuSpecRepository cpus, MotherboardSpecRepository motherboards,
                               RamSpecRepository ram, GpuSpecRepository gpus,
                               MonitorSpecRepository monitors, StorageSpecRepository storage,
                               CatalogProductSourceRepository sources, SharedPriceAdapter sharedPrices) {
        this.productService = productService;
        this.products = products;
        this.cpus = cpus;
        this.motherboards = motherboards;
        this.ram = ram;
        this.gpus = gpus;
        this.monitors = monitors;
        this.storage = storage;
        this.sources = sources;
        this.sharedPrices = sharedPrices;
    }

    @Transactional
    public CatalogEntryView create(CatalogEntryCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Catalog entry request is required");
        }
        for (var source : request.sources()) {
            if (source.externalId() != null
                    && sources.existsBySourceNameAndExternalId(source.sourceName(), source.externalId())) {
                throw new IllegalArgumentException("Source is already registered: "
                        + source.sourceName() + "/" + source.externalId());
            }
        }

        // 다른 서비스의 REQUIRED 트랜잭션도 현재 트랜잭션에 참여한다. 여기서 전체 커밋 여부가 결정된다.
        var productView = productService.create(request.product());
        var product = products.getReferenceById(productView.id());
        var specification = request.specification();
        if (specification instanceof CatalogSpecification.Cpu cpu) {
            cpus.saveAndFlush(new CpuSpec(product, cpu));
        } else if (specification instanceof CatalogSpecification.Motherboard motherboard) {
            motherboards.saveAndFlush(new MotherboardSpec(product, motherboard));
        } else if (specification instanceof CatalogSpecification.Ram memory) {
            ram.saveAndFlush(new RamSpec(product, memory));
        } else if (specification instanceof CatalogSpecification.Gpu gpu) {
            // 보조전원 목록도 제원에 딸린 컬렉션으로 같은 트랜잭션에서 저장한다.
            gpus.saveAndFlush(new GpuSpec(product, gpu));
        } else if (specification instanceof CatalogSpecification.Monitor monitor) {
            monitors.saveAndFlush(new MonitorSpec(product, monitor));
        } else if (specification instanceof CatalogSpecification.Storage drive) {
            storage.saveAndFlush(new StorageSpec(product, drive));
        } else {
            throw new IllegalArgumentException("Unsupported catalog specification type");
        }

        for (var source : request.sources()) {
            // 같은 외부 ID를 동시에 등록하는 요청은 DB의 UNIQUE 제약이 최종 차단한다.
            // 마지막 출처 INSERT가 실패해도 앞서 실행한 제품·가격·제원 INSERT가 모두 롤백된다.
            sources.saveAndFlush(new CatalogProductSource(product, source));
        }
        return loadEntry(productView, false);
    }

    public Optional<CatalogEntryView> findById(String id) {
        return productService.findById(id).map(product -> loadEntry(product, true));
    }

    private CatalogEntryView loadEntry(CatalogProductView product, boolean sharedLookup) {
        var cpu = cpus.findById(product.id());
        var motherboard = motherboards.findById(product.id());
        var memory = ram.findById(product.id());
        var gpu = gpus.findById(product.id());
        var monitor = monitors.findById(product.id());
        var drive = storage.findById(product.id());
        int specificationCount = (cpu.isPresent() ? 1 : 0)
                + (motherboard.isPresent() ? 1 : 0) + (memory.isPresent() ? 1 : 0)
                + (gpu.isPresent() ? 1 : 0) + (monitor.isPresent() ? 1 : 0)
                + (drive.isPresent() ? 1 : 0);
        if (specificationCount != 1) {
            throw new IllegalStateException("Catalog entry requires exactly one specification: " + product.id());
        }

        CatalogSpecification specification;
        if (cpu.isPresent()) {
            specification = cpu.get().toSpecification();
        } else if (motherboard.isPresent()) {
            specification = motherboard.get().toSpecification();
        } else if (memory.isPresent()) {
            specification = memory.orElseThrow().toSpecification();
        } else if (gpu.isPresent()) {
            specification = gpu.orElseThrow().toSpecification();
        } else if (monitor.isPresent()) {
            specification = monitor.orElseThrow().toSpecification();
        } else {
            specification = drive.orElseThrow().toSpecification();
        }
        if (product.type() != specification.type()) {
            throw new IllegalStateException("Stored product and specification types do not match: " + product.id());
        }
        var sourceViews = sources.findAllByProduct_IdOrderByIdAsc(product.id()).stream()
                .map(CatalogProductSource::toView).toList();
        if (sourceViews.isEmpty()) {
            throw new IllegalStateException("Catalog entry is missing its sources: " + product.id());
        }
        if (sharedLookup) {
            var counts = memory.isPresent() ? java.util.Collections.singletonMap(product.id(),
                    memory.orElseThrow().toSpecification().moduleCount()) : java.util.Map.<String, Integer>of();
            product = sharedPrices.enrich(java.util.List.of(product), counts).getFirst();
        }
        return new CatalogEntryView(product, specification, sourceViews);
    }
}
