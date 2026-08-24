package com.fintrack.api.dto.category;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PATCH semantics: a null field means "leave unchanged", not "set to null".
 * <p>
 * The type is deliberately not updatable. Flipping a category from EXPENSE to INCOME
 * would silently reinterpret every transaction already filed under it.
 */
public record UpdateCategoryRequest(

        @Size(min = 1, max = 80, message = "Name must be between 1 and 80 characters")
        String name,

        @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "Color must be a hex value like #22c55e")
        String color,

        @Size(max = 40, message = "Icon must be at most 40 characters")
        String icon
) {}
