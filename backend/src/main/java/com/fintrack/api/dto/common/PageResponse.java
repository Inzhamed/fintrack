package com.fintrack.api.dto.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * The envelope every list endpoint returns: {@code {"data": [...], "meta": {...}}}.
 * <p>
 * Spring Data's own {@code Page} serialises its full internal shape - including
 * {@code pageable}, {@code sort} and a pile of redundant booleans - which is unstable
 * across upgrades and leaks the persistence layer into the API contract. This exposes
 * only what a client needs to render a pager.
 */
public record PageResponse<T>(List<T> data, Meta meta) {

    /**
     * @param page       zero-based index of this page
     * @param size       requested page size
     * @param total      total matching rows across all pages
     * @param totalPages number of pages at this size
     */
    public record Meta(int page, int size, long total, int totalPages, boolean hasNext) {}

    /** Wraps a {@link Page} of entities, mapping each one to its response DTO. */
    public static <E, D> PageResponse<D> from(Page<E> page, Function<E, D> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                new Meta(page.getNumber(), page.getSize(), page.getTotalElements(),
                        page.getTotalPages(), page.hasNext()));
    }
}
