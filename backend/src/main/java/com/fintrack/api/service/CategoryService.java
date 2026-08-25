package com.fintrack.api.service;

import com.fintrack.api.dto.category.CategoryResponse;
import com.fintrack.api.dto.category.CreateCategoryRequest;
import com.fintrack.api.dto.category.UpdateCategoryRequest;
import com.fintrack.api.exception.ApiException;
import com.fintrack.api.exception.ErrorCode;
import com.fintrack.api.model.Category;
import com.fintrack.api.model.EntryType;
import com.fintrack.api.model.User;
import com.fintrack.api.repository.CategoryRepository;
import com.fintrack.api.repository.TransactionRepository;
import com.fintrack.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Categories, both the seeded globals and the user's own.
 * <p>
 * Every method takes the caller's id and scopes its query by it. Ownership is a query
 * predicate, not a check performed after loading - "load by id, then compare the owner"
 * leaks existence through timing and is easy to forget on one branch out of five.
 */
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final CacheInvalidator cacheInvalidator;

    /** Globals first, then the user's own, each alphabetically. */
    @Transactional(readOnly = true)
    public List<CategoryResponse> list(UUID userId, EntryType type) {
        return categoryRepository.findVisibleTo(userId, type).stream()
                .map(CategoryResponse::from)
                .toList();
    }

    @Transactional
    public CategoryResponse create(UUID userId, CreateCategoryRequest request) {
        String name = request.name().trim();

        if (categoryRepository.existsForUserByNameAndType(userId, name, request.type())) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                    "You already have a %s category called '%s'"
                            .formatted(request.type().name().toLowerCase(), name));
        }

        User owner = userRepository.getReferenceById(userId);

        Category category = Category.builder()
                .owner(owner)
                .name(name)
                .type(request.type())
                .color(request.color())
                .icon(request.icon())
                .build();

        return CategoryResponse.from(categoryRepository.saveAndFlush(category));
    }

    /**
     * Partial update. Only a category the user owns can be edited - the globals are shared
     * by every account, so letting one user rename "Groceries" would rename it for all.
     */
    @Transactional
    public CategoryResponse update(UUID userId, UUID categoryId, UpdateCategoryRequest request) {
        Category category = ownedOrFail(userId, categoryId);

        if (request.name() != null) {
            String name = request.name().trim();
            // Only check for a clash if the name actually changed, so that PATCHing a
            // category with its own current name is not a conflict with itself.
            if (!name.equalsIgnoreCase(category.getName())
                    && categoryRepository.existsForUserByNameAndType(userId, name, category.getType())) {
                throw new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                        "You already have a category called '%s'".formatted(name));
            }
            category.setName(name);
        }
        if (request.color() != null) {
            category.setColor(request.color());
        }
        if (request.icon() != null) {
            category.setIcon(request.icon());
        }

        cacheInvalidator.evictAnalyticsFor(userId);
        return CategoryResponse.from(category);
    }

    /**
     * Deletes a user-owned category.
     * <p>
     * The schema sets {@code transactions.category_id} to NULL on delete, so history is
     * preserved and the affected transactions simply become uncategorised. That is a
     * surprising amount of silent data change to trigger from a DELETE, so the caller has
     * to opt into it explicitly once transactions exist.
     */
    @Transactional
    public void delete(UUID userId, UUID categoryId, boolean force) {
        Category category = ownedOrFail(userId, categoryId);

        if (!force && transactionRepository.existsByCategoryId(categoryId)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "This category is still used by existing transactions. "
                            + "Re-send with ?force=true to delete it and leave those transactions uncategorised.");
        }

        categoryRepository.delete(category);
        cacheInvalidator.evictAnalyticsFor(userId);
    }

    /**
     * Resolves a category the user is allowed to file a transaction under, and checks the
     * direction matches. An expense booked against "Salary" is a data-entry slip the
     * domain can reject outright, which is the reason both share {@link EntryType}.
     */
    @Transactional(readOnly = true)
    public Category resolveUsable(UUID userId, UUID categoryId, EntryType entryType) {
        Category category = categoryRepository.findVisibleToUser(categoryId, userId)
                .orElseThrow(() -> ApiException.notFound("Category", categoryId));

        if (category.getType() != entryType) {
            throw ApiException.validation(
                    "Category '%s' is an %s category and cannot be used for an %s"
                            .formatted(category.getName(),
                                    category.getType().name().toLowerCase(),
                                    entryType.name().toLowerCase()),
                    java.util.Map.of("categoryId", categoryId.toString(),
                            "categoryType", category.getType().name(),
                            "expected", entryType.name()));
        }
        return category;
    }

    private Category ownedOrFail(UUID userId, UUID categoryId) {
        return categoryRepository.findOwnedBy(categoryId, userId)
                // A global category exists but is not the user's, so 404 rather than 403:
                // the response is identical whether the id is unknown or simply not theirs.
                .orElseThrow(() -> ApiException.notFound("Category", categoryId));
    }
}
