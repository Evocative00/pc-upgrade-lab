package com.pcupgradelab.pc;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/**
 * PC 등록·조회·수정 API에서 주고받는 요청 및 응답 DTO.
 * docs/week1-contract.md의 공통 규격 및 프런트엔드 규격을 준수한다.
 */
public final class PcDtos {
    private PcDtos() { }

    /**
     * PC 등록 및 수정 요청 DTO.
     * name은 공백만 사용할 수 없고 최대 100자, parts는 1~64개여야 한다.
     */
    public record Request(
            @NotBlank(message = "PC 이름은 필수입니다.")
            @Size(max = 100, message = "PC 이름은 최대 100자까지 가능합니다.")
            String name,

            @NotEmpty(message = "부품 목록은 비어 있을 수 없습니다.")
            @Size(max = 64, message = "부품은 1개에서 64개까지 포함할 수 있습니다.")
            List<@Valid @NotNull PartInput> parts) {
        public Request {
            if (parts != null) {
                parts = List.copyOf(parts);
            }
        }
    }

    /**
     * PC 목록 조회용 요약 DTO.
     * 목록에서는 부품 목록 조회를 생략하여 성능을 최적화한다.
     */
    public record Summary(
            Long id,
            String name,
            Instant createdAt,
            Instant updatedAt) {
        public static Summary from(PcConfiguration pc) {
            return new Summary(pc.getId(), pc.getName(), pc.getCreatedAt(), pc.getUpdatedAt());
        }
    }

    /**
     * PC 상세 응답 DTO.
     * 등록, 단건 조회, 수정 응답에 사용하며 DB 내부 부품 행 ID는 제외하고 PartInput 목록을 반환한다.
     */
    public record Detail(
            Long id,
            String name,
            List<PartInput> parts,
            Instant createdAt,
            Instant updatedAt) {
        public static Detail from(PcConfiguration pc) {
            return new Detail(
                    pc.getId(),
                    pc.getName(),
                    pc.getParts().stream().map(PcPart::toInput).toList(),
                    pc.getCreatedAt(),
                    pc.getUpdatedAt()
            );
        }
    }

    /**
     * PC 목록 페이징 응답 DTO.
     * docs/week1-contract.md에 명시된 {items, page, size, totalElements, totalPages} 구조를 제공한다.
     */
    public record PageResponse<T>(
            List<T> items,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }
}
