package com.fintrack.api.dto.category;

import com.fintrack.api.model.Category;
import com.fintrack.api.model.EntryType;

import java.util.UUID;

/**
 * @param global true for the seeded defaults shared by every account. The frontend uses
 *               this to hide edit and delete controls, since globals are read-only.
 */
public record CategoryResponse(
        UUID id,
        String name,
        EntryType type,
        String color,
        String icon,
        boolean global
) {
    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getType(),
                category.getColor(),
                category.getIcon(),
                category.isGlobal());
    }
}
