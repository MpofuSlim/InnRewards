package com.innbucks.loyaltyservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "PageResponse", description = "Paginated payload wrapper used inside ApiResult.data for list endpoints.")
public class PageResponse<T> {

    @Schema(description = "Page contents.")
    private List<T> content;

    @Schema(example = "0", description = "Zero-indexed page number.")
    private int page;

    @Schema(example = "20", description = "Page size.")
    private int size;

    @Schema(example = "0", description = "Total number of elements across all pages.")
    private long totalElements;

    @Schema(example = "0", description = "Total number of pages.")
    private int totalPages;

    @Schema(example = "true", description = "Whether this is the first page.")
    private boolean first;

    @Schema(example = "true", description = "Whether this is the last page.")
    private boolean last;

    /** Wrap a Spring Data {@link Page} into the envelope-friendly shape. */
    public static <T> PageResponse<T> from(Page<T> p) {
        return PageResponse.<T>builder()
                .content(p.getContent())
                .page(p.getNumber())
                .size(p.getSize())
                .totalElements(p.getTotalElements())
                .totalPages(p.getTotalPages())
                .first(p.isFirst())
                .last(p.isLast())
                .build();
    }

    /**
     * Slice an in-memory list using the provided {@link Pageable}. Used for
     * derived/aggregated lists where pushing pagination into the DB query is
     * not yet justified by dataset size.
     */
    public static <T> PageResponse<T> from(List<T> list, Pageable pageable) {
        int total = list == null ? 0 : list.size();
        int size = pageable.isPaged() ? pageable.getPageSize() : Math.max(total, 1);
        int page = pageable.isPaged() ? pageable.getPageNumber() : 0;
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        List<T> slice = list == null ? List.of() : list.subList(from, to);
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / (double) size);
        return PageResponse.<T>builder()
                .content(slice)
                .page(page)
                .size(size)
                .totalElements(total)
                .totalPages(totalPages)
                .first(page == 0)
                .last(page + 1 >= totalPages || totalPages == 0)
                .build();
    }
}
