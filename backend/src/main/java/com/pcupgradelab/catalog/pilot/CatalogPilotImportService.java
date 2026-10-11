package com.pcupgradelab.catalog.pilot;

import com.pcupgradelab.catalog.*;
import com.pcupgradelab.catalog.identity.*;
import com.pcupgradelab.catalog.memory.CpuMemorySupport;
import com.pcupgradelab.catalog.memory.CpuMemorySupportRepository;
import com.pcupgradelab.catalog.price.*;
import com.pcupgradelab.catalog.storage.*;
import com.pcupgradelab.catalog.support.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit reviewed pilot only. One transaction; immutable evidence and existing specifications. */
@Service
public class CatalogPilotImportService {
    private final CatalogEntryService entries;
    private final CatalogProductRepository products;
    private final CatalogProductSourceRepository sources;
    private final CatalogModelRepository models;
    private final CatalogModelSourceRepository modelSources;
    private final CatalogIdentityProductRepository identities;
    private final CatalogIdentityService identity;
    private final CpuMemorySupportRepository memory;
    private final MotherboardCpuSupportRepository cpuSupport;
    private final MotherboardStorageProfileRepository storage;
    private final MotherboardStorageService storageService;
    private final CatalogPriceImportService prices;
    private final CatalogPriceMappingRepository mappings;
    private final CatalogPriceDatabaseTimeZone timeZone;
    private final EntityManager entityManager;

    public CatalogPilotImportService(CatalogEntryService entries, CatalogProductRepository products,
            CatalogProductSourceRepository sources, CatalogModelRepository models,
            CatalogModelSourceRepository modelSources, CatalogIdentityProductRepository identities,
            CatalogIdentityService identity, CpuMemorySupportRepository memory,
            MotherboardCpuSupportRepository cpuSupport, MotherboardStorageProfileRepository storage,
            MotherboardStorageService storageService, CatalogPriceImportService prices,
            CatalogPriceMappingRepository mappings, CatalogPriceDatabaseTimeZone timeZone, EntityManager entityManager) {
        this.entries=entries; this.products=products; this.sources=sources; this.models=models;
        this.modelSources=modelSources; this.identities=identities; this.identity=identity; this.memory=memory;
        this.cpuSupport=cpuSupport; this.storage=storage; this.storageService=storageService;
        this.prices=prices; this.mappings=mappings; this.timeZone=timeZone; this.entityManager=entityManager;
    }

    public record Result(boolean dryRun, int createdProducts, int createdModels, int createdProductSources,
            int boundProducts, int storageProfiles, int storageSlots, int cpuMemoryProfiles,
            int cpuSupportPairs, int newPriceMappings, int newPriceObservations) { }

    @Transactional(readOnly=true)
    public Result preview(CatalogPilotImportLoader.Plan plan) { return timeZone.read(() -> process(plan, false)); }

    @Transactional
    public Result apply(CatalogPilotImportLoader.Plan plan) { return timeZone.write(() -> process(plan, true)); }

    private Result process(CatalogPilotImportLoader.Plan plan, boolean apply) {
        if (plan == null || plan.parts().size()!=14 || plan.models().size()!=13
                || plan.cpuSupportPairs().size()!=8 || plan.prices().items().size()!=8)
            throw new IllegalArgumentException("Only the approved 14-part/8-price pilot is supported");
        var resolved = new HashMap<Integer, CatalogProduct>();
        int newProducts=0, newModels=0, newSources=0, bindings=0, profiles=0, slots=0, memories=0, pairs=0;
        // All existing identities, specifications and evidence are checked before creating any row.
        for (var part : plan.parts()) {
            var original=sources.findBySourceNameAndExternalId(part.sourceIdentity().sourceName(), part.sourceIdentity().externalId());
            if (original.isEmpty()) {
                if (part.sourceIdentity().sourceName()==CatalogSourceName.BUILDCORES)
                    throw new IllegalArgumentException("An approved existing pilot product is missing");
                newProducts++;
            } else {
                var product=original.orElseThrow().getProduct();
                if (apply) entityManager.refresh(product, LockModeType.PESSIMISTIC_WRITE);
                var entry=entries.findById(product.getId()).orElseThrow();
                if (product.getType()!=part.product().type() || !product.getManufacturer().equals(part.product().manufacturer())
                        || !product.getModelName().equals(part.product().modelName())
                        || !Objects.equals(product.getPartNumber(),part.product().partNumber())
                        || !entry.specification().equals(part.specification()))
                    throw new IllegalArgumentException("Existing pilot product identity or specification differs");
                resolved.put(part.selectionNumber(), product);
            }
            var product=resolved.get(part.selectionNumber());
            var evidence=sources.findBySourceNameAndExternalId(part.manufacturerEvidence().sourceName(), part.manufacturerEvidence().externalId());
            if (evidence.isEmpty()) newSources++;
            else if (product==null || !sourceMatches(evidence.orElseThrow(),product.getId(),part.manufacturerEvidence()))
                throw new IllegalArgumentException("Existing pilot manufacturer evidence differs");
            var sameCanonical=identities.findByCanonicalId(part.proposedCanonicalId());
            if (sameCanonical.isPresent() && (product==null || !sameCanonical.orElseThrow().getId().equals(product.getId())))
                throw new IllegalArgumentException("Pilot canonical ID belongs to another product");
            var model=models.findByCanonicalId(part.modelCanonicalId()).orElse(null);
            if (product!=null) checkBinding(product,part,model,evidence.orElse(null));
            if (product==null || !fullyBound(product,part,model)) bindings++;
            if (part.storageSupport()!=null) {
                var existing=product==null ? List.<MotherboardStorageProfile>of() : storage.findAllByMotherboard_IdOrderByRevisionKeyAsc(product.getId());
                var profile=existing.stream().filter(row -> row.support().revisionKey().equals(part.storageSupport().revisionKey())).findFirst();
                if (profile.isEmpty()) { profiles++; slots+=part.storageSupport().slots().size(); }
                else if (!profile.orElseThrow().support().equals(part.storageSupport())) throw new IllegalArgumentException("Existing pilot storage profile differs");
            }
            if (part.memorySupport()!=null) {
                var existing=product==null ? Optional.<CpuMemorySupportRepository.Stored>empty() : memory.findByProductId(product.getId());
                if (existing.isEmpty()) memories++;
                else if (part.sourceIdentity().sourceName()==CatalogSourceName.MANUFACTURER
                        ? !existing.orElseThrow().support().equals(part.memorySupport())
                            || evidence.isEmpty() || existing.orElseThrow().evidenceSourceId()!=evidence.orElseThrow().getId()
                        : !sameMemoryFacts(existing.orElseThrow().support(),part.memorySupport()))
                    throw new IllegalArgumentException("Existing CPU memory hardware facts differ; no overwrite");
            }
        }
        for (var input : plan.models()) {
            if (models.findByCanonicalId(input.canonicalId()).isPresent()) {
                // Existing registration path performs exact comparison and never changes a matching model.
                identity.verifyModel(input);
            } else {
                for (var source : input.sources()) {
                    if (modelSources.findBySourceNameAndExternalId(source.source().sourceName(),source.source().externalId()).isPresent())
                        throw new IllegalArgumentException("Pilot model evidence belongs to another model");
                }
                newModels++;
            }
        }
        var grouped=groupPairs(plan);
        for (var group : grouped.entrySet()) {
            var board=resolved.get(group.getKey());
            var existing=board==null ? Optional.<MotherboardCpuSupportRepository.Stored>empty() : cpuSupport.findByRevision(board.getId(),"MODEL");
            if (existing.isEmpty()) pairs+=group.getValue().size();
            else for (var pair : group.getValue()) {
                var cpu=resolved.get(pair.cpuSelectionNumber());
                if (cpu==null || !existing.orElseThrow().support().entries().contains(entry(pair,cpu.getId())))
                    throw new IllegalArgumentException("Existing pilot CPU support differs; no implicit append or overwrite");
            }
        }
        // New prices reference new products only after their exact source is registered.
        var existingPrices=new ArrayList<CatalogPriceImportBatch.Item>();
        int newMappings=0, newObservations=0;
        for (var item : plan.prices().items()) {
            var part=plan.parts().stream().filter(p -> p.sourceIdentity().sourceName()==item.product().sourceName()
                    && p.sourceIdentity().externalId().equals(item.product().externalId())).findFirst().orElseThrow();
            if (!part.product().manufacturer().equals(item.product().manufacturer())
                    || !part.product().modelName().equals(item.product().modelName())
                    || !Objects.equals(part.product().partNumber(),item.product().partNumber()))
                throw new IllegalArgumentException("Pilot price identity differs from its approved product");
            if (resolved.containsKey(part.selectionNumber())) existingPrices.add(item);
            else {
                if (mappings.findBySourceNameAndExternalId(item.offer().sourceName(),item.offer().externalId()).isPresent())
                    throw new IllegalArgumentException("New pilot provider identity is already mapped");
                newMappings++; newObservations++;
            }
        }
        if (!existingPrices.isEmpty()) {
            var counts=prices.preview(new CatalogPriceImportBatch(1,existingPrices));
            newMappings+=counts.createdMappings(); newObservations+=counts.createdObservations();
        }
        var result=new Result(!apply,newProducts,newModels,newSources,bindings,profiles,slots,memories,pairs,newMappings,newObservations);
        if (!apply) return result;
        var modelIds=new HashMap<String,String>();
        for (var input : plan.models()) modelIds.put(input.canonicalId(),identity.registerModel(input).id());
        for (var part : plan.parts()) {
            var product=resolved.get(part.selectionNumber());
            if (product==null) {
                var created=entries.create(new CatalogEntryCreateRequest(part.product(),part.specification(),List.of(part.manufacturerEvidence())));
                product=products.findById(created.product().id()).orElseThrow();
                resolved.put(part.selectionNumber(),product);
            }
            var evidence=sources.findBySourceNameAndExternalId(part.manufacturerEvidence().sourceName(),part.manufacturerEvidence().externalId())
                    .orElseGet(() -> sources.saveAndFlush(new CatalogProductSource(resolved.get(part.selectionNumber()),part.manufacturerEvidence())));
            identity.bindProduct(new CatalogIdentityRequests.ProductBinding(product.getId(),part.proposedCanonicalId(),
                    modelIds.get(part.modelCanonicalId()),part.identityKind(),part.role(),evidence.getId(),part.bindingReviewScope()));
            if (part.storageSupport()!=null && !storage.existsByMotherboard_IdAndRevisionKey(product.getId(),part.storageSupport().revisionKey()))
                storageService.register(product.getId(),part.storageSupport());
            if (part.memorySupport()!=null && memory.findByProductId(product.getId()).isEmpty())
                memory.insert(product.getId(),evidence.getId(),part.memorySupport());
        }
        for (var group : grouped.entrySet()) {
            var board=resolved.get(group.getKey());
            if (cpuSupport.findByRevision(board.getId(),"MODEL").isPresent()) continue;
            var part=plan.parts().stream().filter(p -> p.selectionNumber()==group.getKey()).findFirst().orElseThrow();
            var source=sources.findBySourceNameAndExternalId(part.manufacturerEvidence().sourceName(),part.manufacturerEvidence().externalId()).orElseThrow();
            var support=new MotherboardCpuSupport(MotherboardCpuSupport.RevisionScope.MODEL,null,
                    "Pilot manufacturer excerpts; hardware revision and installed BIOS are unknown. UNVERIFIED pairs remain unknown.",
                    group.getValue().stream().map(pair -> entry(pair,resolved.get(pair.cpuSelectionNumber()).getId())).toList());
            cpuSupport.insert(board.getId(),source.getId(),support);
        }
        prices.apply(plan.prices());
        entityManager.flush();
        return result;
    }

    private void checkBinding(CatalogProduct product, CatalogPilotImportLoader.Part part, CatalogModel model, CatalogProductSource evidence) {
        if ((product.getCanonicalId()!=null && !product.getCanonicalId().equals(part.proposedCanonicalId()))
                || (product.getModelId()!=null && (model==null || !product.getModelId().equals(model.getId())))
                || (product.getIdentityKind()!=CatalogIdentityKind.LEGACY_UNCLASSIFIED && product.getIdentityKind()!=part.identityKind())
                || (product.getRole()!=CatalogRole.UNASSIGNED && product.getRole()!=part.role()))
            throw new IllegalArgumentException("Existing pilot binding differs; no overwrite");
        if (fullyBound(product,part,model) && (evidence==null
                || !Objects.equals(product.getIdentityEvidenceSourceId(),evidence.getId())
                || !Objects.equals(product.getIdentityReviewScope(),part.bindingReviewScope())))
            throw new IllegalArgumentException("Existing identity review differs; no overwrite");
    }

    private static boolean fullyBound(CatalogProduct product,CatalogPilotImportLoader.Part part,CatalogModel model) {
        return model!=null && Objects.equals(product.getCanonicalId(),part.proposedCanonicalId())
                && Objects.equals(product.getModelId(),model.getId()) && product.getIdentityKind()==part.identityKind() && product.getRole()==part.role();
    }

    private static Map<Integer,List<CatalogPilotImportLoader.CpuSupportPair>> groupPairs(CatalogPilotImportLoader.Plan plan) {
        var groups=new LinkedHashMap<Integer,List<CatalogPilotImportLoader.CpuSupportPair>>();
        for (var pair : plan.cpuSupportPairs()) groups.computeIfAbsent(pair.boardSelectionNumber(),key -> new ArrayList<>()).add(pair);
        return groups;
    }
    private static MotherboardCpuSupport.Entry entry(CatalogPilotImportLoader.CpuSupportPair pair,String cpuId) {
        var input=pair.seedEntryJson();
        return new MotherboardCpuSupport.Entry(cpuId,input.path("variantKey").stringValue(),
                MotherboardCpuSupport.SupportStatus.valueOf(input.path("supportStatus").stringValue()),input.path("reportedCpuName").stringValue(),
                optional(input,"cpuStepping"),MotherboardCpuSupport.BiosRequirement.valueOf(input.path("biosRequirement").stringValue()),
                optional(input,"minimumBiosVersion"),optional(input,"manufacturerBiosLabel"),input.path("sourceUrl").stringValue(),input.path("conditions").stringValue());
    }
    private static String optional(tools.jackson.databind.JsonNode input,String name) { return input.path(name).isString() ? input.path(name).stringValue() : null; }
    private static boolean sameMemoryFacts(CpuMemorySupport left,CpuMemorySupport right) {
        return left.memoryTypesKnown()==right.memoryTypesKnown() && Objects.equals(left.maxMemoryBytes(),right.maxMemoryBytes())
                && Objects.equals(left.channelCount(),right.channelCount()) && left.supportedTypes().size()==right.supportedTypes().size()
                && java.util.stream.IntStream.range(0,left.supportedTypes().size()).allMatch(i ->
                    left.supportedTypes().get(i).memoryType()==right.supportedTypes().get(i).memoryType()
                    && Objects.equals(left.supportedTypes().get(i).maxStandardDataRateMts(),right.supportedTypes().get(i).maxStandardDataRateMts()));
    }
    private static boolean sourceMatches(CatalogProductSource actual,String productId,CatalogSourceInput expected) {
        return actual.getProduct().getId().equals(productId) && actual.getSourceName()==expected.sourceName()
                && Objects.equals(actual.getExternalId(),expected.externalId()) && Objects.equals(actual.getSourceRevision(),expected.sourceRevision())
                && actual.getSourceUrl().equals(expected.sourceUrl()) && actual.getRetrievedAt().equals(expected.retrievedAt())
                && sameJson(actual.getRawPayload(),expected.rawPayload());
    }
    private static boolean sameJson(Object left,Object right) {
        if (left instanceof Number l && right instanceof Number r) return new BigDecimal(l.toString()).compareTo(new BigDecimal(r.toString()))==0;
        if (left instanceof Map<?,?> l && right instanceof Map<?,?> r) return l.keySet().equals(r.keySet()) && l.keySet().stream().allMatch(k -> sameJson(l.get(k),r.get(k)));
        if (left instanceof List<?> l && right instanceof List<?> r) return l.size()==r.size() && java.util.stream.IntStream.range(0,l.size()).allMatch(i -> sameJson(l.get(i),r.get(i)));
        return Objects.equals(left,right);
    }
}
