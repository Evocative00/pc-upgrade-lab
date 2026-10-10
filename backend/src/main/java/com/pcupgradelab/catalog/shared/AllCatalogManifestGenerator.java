package com.pcupgradelab.catalog.shared;

import com.pcupgradelab.catalog.*;
import com.pcupgradelab.catalog.identity.*;
import com.pcupgradelab.catalog.pilot.CatalogPilotImportLoader;
import com.pcupgradelab.catalog.price.*;
import com.pcupgradelab.catalog.seed.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Authoritative seed and approved pilot inputs only; never reads a live database or private settings. */
public final class AllCatalogManifestGenerator {
    public static final String IDENTITIES_FILE = "data/catalog-shared/all-catalog-identities-2026-10-10.json";
    public static final String PRICES_FILE = "data/catalog-shared/approved-prices-2026-10-10.json";
    private static final Map<String,String> APPROVED_PRICE_HASHES = Map.of(
            "observations-2026-10-06.json", "b2fd9c8538af6161921edfc1c37f6d12c3c1b6218af2dad74562fd1d0b53bf84",
            "observations-expansion50-2026-10-08.json", "de6f16277a2092b5ecdfb5342c3328648b0e59b00c8a479e440bb924058260c0");

    public record SourceIdentity(CatalogSourceName sourceName, String externalId) {
        public SourceIdentity {
            if (sourceName == null || externalId == null || externalId.isBlank())
                throw new IllegalArgumentException("A reviewed source identity is required");
        }
    }
    public record Product(SourceIdentity sourceIdentity, SharedPriceDtos.Identity identity) { }
    public record Manifest(int schemaVersion, List<Product> products) {
        public Manifest { products = List.copyOf(products); }
    }
    public record ProductPlan(SourceIdentity sourceIdentity, SharedPriceDtos.Identity identity,
                              CatalogSpecification specification, CatalogSourceInput evidence, boolean bindLegacy) { }
    public record Plan(Manifest manifest, CatalogPriceImportBatch prices, List<ProductPlan> products) {
        public Plan { products = List.copyOf(products); }
    }

    public Plan load(Path repositoryRoot) {
        if (repositoryRoot == null) throw new IllegalArgumentException("Repository root is required");
        var bySource = new LinkedHashMap<SourceIdentity,ProductPlan>();
        var seeds = new CatalogSeedLoader();
        for (var batch : CatalogSeedBatch.values()) {
            for (var entry : seeds.load(batch)) {
                var source = entry.sources().stream().filter(s -> s.sourceName() == CatalogSourceName.BUILDCORES)
                        .findFirst().orElseThrow();
                var key = new SourceIdentity(source.sourceName(),source.externalId());
                var identity = identity(CanonicalCatalogIds.productForReviewedSource(key.sourceName(),key.externalId()),
                        entry.product(),entry.specification(),CatalogIdentityKind.LEGACY_UNCLASSIFIED,CatalogRole.UNASSIGNED);
                require(bySource.putIfAbsent(key,new ProductPlan(key,identity,entry.specification(),source,true)) == null,
                        "Duplicate authoritative catalog source");
            }
        }
        require(bySource.size() == 300,"Authoritative catalog must contain exactly 300 seed products");
        var pilot = new CatalogPilotImportLoader().load(repositoryRoot);
        int reused = 0;
        for (var part : pilot.parts()) {
            var key = new SourceIdentity(part.sourceIdentity().sourceName(),part.sourceIdentity().externalId());
            var identity = identity(part.proposedCanonicalId(),part.product(),part.specification(),part.identityKind(),part.role());
            var original = bySource.get(key);
            if (original != null) {
                require(original.identity().canonicalId().equals(identity.canonicalId())
                        && sameProduct(original.identity(),identity) && original.specification().equals(part.specification()),
                        "Pilot reuse differs from the authoritative seed");
                reused++;
            } else require(key.sourceName() == CatalogSourceName.MANUFACTURER,"New pilot product must have manufacturer identity");
            bySource.put(key,new ProductPlan(key,identity,part.specification(),part.manufacturerEvidence(),false));
        }
        require(reused == 7 && bySource.size() == 307,"Reviewed catalog scope must remain 300 plus seven products");
        var products = bySource.values().stream().sorted(Comparator.comparing(p -> p.identity().canonicalId())).toList();
        require(products.stream().map(p -> p.identity().canonicalId()).distinct().count() == 307,"Duplicate canonical catalog identity");
        var prices = new LinkedHashMap<SourceIdentity,CatalogPriceImportBatch.Item>();
        var priceLoader = new CatalogPriceImportLoader();
        for (String file : List.of("observations-2026-10-06.json","observations-expansion50-2026-10-08.json")) {
            Path path = repositoryRoot.resolve("data/catalog-current-prices/" + file);
            requirePinned(path,APPROVED_PRICE_HASHES.get(file));
            for (var item : priceLoader.load(path).items()) {
                var key = source(item);
                require(prices.putIfAbsent(key,item) == null,"Duplicate approved original price identity");
            }
        }
        require(prices.size() == 72,"Original approved prices must contain exactly 72 products");
        int replaced = 0;
        for (var item : pilot.prices().items()) {
            var previous = prices.put(source(item),item);
            if (previous != null) {
                require(!item.price().observedAt().isBefore(previous.price().observedAt()),"Pilot cannot replace a newer observation");
                replaced++;
            }
        }
        require(replaced == 6 && prices.size() == 74,"Approved price merge must retain 74 products and six newer observations");
        for (var item : prices.values()) {
            var product = bySource.get(source(item));
            require(product != null,"Price has no authoritative catalog identity");
            validatePrice(product.identity(),item);
        }
        var orderedPrices = prices.entrySet().stream().sorted(Comparator.comparing(e -> bySource.get(e.getKey()).identity().canonicalId()))
                .map(Map.Entry::getValue).toList();
        return new Plan(new Manifest(1,products.stream().map(p -> new Product(p.sourceIdentity(),p.identity())).toList()),
                new CatalogPriceImportBatch(1,orderedPrices),products);
    }

    private static SharedPriceDtos.Identity identity(String canonical,CatalogProductCreateRequest product,
            CatalogSpecification specification,CatalogIdentityKind kind,CatalogRole role) {
        Integer modules = specification instanceof CatalogSpecification.Ram ram ? ram.moduleCount() : null;
        return new SharedPriceDtos.Identity(canonical,product.type(),product.manufacturer(),product.modelName(),product.partNumber(),
                kind,role,CatalogVerificationStatus.UNVERIFIED,false,modules != null && modules > 1 ? "RAM_KIT" : "PRODUCT",modules);
    }
    private static boolean sameProduct(SharedPriceDtos.Identity left,SharedPriceDtos.Identity right) {
        return left.type() == right.type() && left.manufacturer().equals(right.manufacturer())
                && left.modelName().equals(right.modelName()) && Objects.equals(left.partNumber(),right.partNumber())
                && Objects.equals(left.moduleCount(),right.moduleCount()) && left.saleUnit().equals(right.saleUnit());
    }
    static SourceIdentity source(CatalogPriceImportBatch.Item item) {
        return new SourceIdentity(item.product().sourceName(),item.product().externalId());
    }
    static void validatePrice(SharedPriceDtos.Identity identity,CatalogPriceImportBatch.Item item) {
        require(identity.manufacturer().equals(item.product().manufacturer()) && identity.modelName().equals(item.product().modelName())
                && Objects.equals(identity.partNumber(),item.product().partNumber())
                && identity.saleUnit().equals(item.offer().saleUnit().name())
                && Objects.equals("RAM_KIT".equals(identity.saleUnit()) ? identity.moduleCount() : null,item.offer().moduleCount()),
                "Approved price sale identity differs from catalog");
    }
    private static void requirePinned(Path path,String expected) {
        try {
            require(Files.size(path) <= 5 * 1024 * 1024,"Approved observation file is too large");
            String text = Files.readString(path,StandardCharsets.UTF_8).replace("\r\n","\n");
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
            require(expected.equals(hash),"Original approved observation file changed; concrete price review is required");
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalArgumentException("Approved observation file cannot be verified",ex);
        }
    }
    private static void require(boolean condition,String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
