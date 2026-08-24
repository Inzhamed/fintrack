package com.fintrack.api.controller;

import com.fintrack.api.dto.category.CategoryResponse;
import com.fintrack.api.dto.category.CreateCategoryRequest;
import com.fintrack.api.dto.category.UpdateCategoryRequest;
import com.fintrack.api.model.EntryType;
import com.fintrack.api.security.AuthenticatedUser;
import com.fintrack.api.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
@Tag(name = "Categories", description = "Spending and earning buckets")
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    @Operation(summary = "List categories visible to the caller",
            description = "The seeded global defaults plus the caller's own, globals first.")
    public List<CategoryResponse> list(@AuthenticationPrincipal AuthenticatedUser principal,
                                       @RequestParam(required = false) EntryType type) {
        return categoryService.list(principal.id(), type);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a category owned by the caller")
    public CategoryResponse create(@AuthenticationPrincipal AuthenticatedUser principal,
                                   @Valid @RequestBody CreateCategoryRequest request) {
        return categoryService.create(principal.id(), request);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a category the caller owns",
            description = "Global defaults are read-only. Type cannot be changed, since that "
                    + "would reinterpret every transaction already filed under the category.")
    public CategoryResponse update(@AuthenticationPrincipal AuthenticatedUser principal,
                                   @PathVariable UUID id,
                                   @Valid @RequestBody UpdateCategoryRequest request) {
        return categoryService.update(principal.id(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a category the caller owns",
            description = "Refuses with 409 if transactions still reference it. Pass force=true "
                    + "to delete anyway and leave those transactions uncategorised.")
    public void delete(@AuthenticationPrincipal AuthenticatedUser principal,
                       @PathVariable UUID id,
                       @RequestParam(defaultValue = "false") boolean force) {
        categoryService.delete(principal.id(), id, force);
    }
}
