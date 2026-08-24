package com.fintrack.api.dto.category;

import com.fintrack.api.model.EntryType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(

        @NotBlank(message = "Name is required")
        @Size(max = 80, message = "Name must be at most 80 characters")
        String name,

        @NotNull(message = "Type is required and must be EXPENSE or INCOME")
        EntryType type,

        @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "Color must be a hex value like #22c55e")
        String color,

        @Size(max = 40, message = "Icon must be at most 40 characters")
        String icon
) {}
