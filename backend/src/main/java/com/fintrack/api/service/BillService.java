package com.fintrack.api.service;

import com.fintrack.api.dto.bill.BillResponse;
import com.fintrack.api.dto.bill.CreateBillRequest;
import com.fintrack.api.dto.bill.UpdateBillRequest;
import com.fintrack.api.exception.ApiException;
import com.fintrack.api.exception.ErrorCode;
import com.fintrack.api.model.Bill;
import com.fintrack.api.model.Category;
import com.fintrack.api.model.Recurrence;
import com.fintrack.api.model.User;
import com.fintrack.api.repository.BillRepository;
import com.fintrack.api.repository.CategoryRepository;
import com.fintrack.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BillService {

    private final BillRepository billRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<BillResponse> list(UUID userId, LocalDate today) {
        return billRepository.findAllForUser(userId).stream()
                .map(bill -> BillResponse.from(bill, today))
                .toList();
    }

    @Transactional
    public BillResponse create(UUID userId, CreateBillRequest request, LocalDate today) {
        validateRecurrence(request.recurrence(), request.dueMonth());

        String name = request.name().trim();
        if (billRepository.existsByUserIdAndNameIgnoreCase(userId, name)) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                    "You already have a bill called '%s'".formatted(name));
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User", userId));

        Bill bill = Bill.builder()
                .user(user)
                .category(resolveCategory(userId, request.categoryId()))
                .name(name)
                .amount(request.amount())
                .currency(request.currency() == null ? user.getBaseCurrency() : request.currency())
                .recurrence(request.recurrence())
                .dueDay(request.dueDay())
                .dueMonth(request.dueMonth())
                .remindDaysBefore(request.remindDaysBefore() == null ? 3 : request.remindDaysBefore())
                .active(true)
                .build();

        return BillResponse.from(billRepository.saveAndFlush(bill), today);
    }

    @Transactional
    public BillResponse update(UUID userId, UUID billId, UpdateBillRequest request,
                               LocalDate today) {
        Bill bill = ownedOrFail(userId, billId);

        if (request.wantsCategoryCleared() && request.categoryId() != null) {
            throw ApiException.validation("clearCategory and categoryId cannot both be set",
                    Map.of("categoryId", request.categoryId().toString()));
        }

        if (request.name() != null) {
            String name = request.name().trim();
            if (!name.equalsIgnoreCase(bill.getName())
                    && billRepository.existsByUserIdAndNameIgnoreCase(userId, name)) {
                throw new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                        "You already have a bill called '%s'".formatted(name));
            }
            bill.setName(name);
        }
        if (request.amount() != null) {
            bill.setAmount(request.amount());
        }
        if (request.dueDay() != null) {
            bill.setDueDay(request.dueDay());
        }
        if (request.dueMonth() != null) {
            // Only meaningful on a yearly bill; setting it on a monthly one would violate the
            // schema's own consistency check.
            if (bill.getRecurrence() != Recurrence.YEARLY) {
                throw ApiException.validation(
                        "Only a yearly bill has a due month",
                        Map.of("recurrence", bill.getRecurrence().name()));
            }
            bill.setDueMonth(request.dueMonth());
        }
        if (request.remindDaysBefore() != null) {
            bill.setRemindDaysBefore(request.remindDaysBefore());
        }
        if (request.active() != null) {
            bill.setActive(request.active());
        }

        if (request.wantsCategoryCleared()) {
            bill.setCategory(null);
        } else if (request.categoryId() != null) {
            bill.setCategory(resolveCategory(userId, request.categoryId()));
        }

        // Changing when a bill falls due makes the previous reminder record meaningless: the
        // next occurrence is a different date, so the user should be reminded about it.
        if (request.dueDay() != null || request.dueMonth() != null) {
            bill.setLastRemindedFor(null);
        }

        return BillResponse.from(bill, today);
    }

    @Transactional
    public void delete(UUID userId, UUID billId) {
        billRepository.delete(ownedOrFail(userId, billId));
    }

    private void validateRecurrence(Recurrence recurrence, Integer dueMonth) {
        if (recurrence == Recurrence.YEARLY && dueMonth == null) {
            throw ApiException.validation("A yearly bill needs a due month",
                    Map.of("dueMonth", "required for YEARLY"));
        }
        if (recurrence == Recurrence.MONTHLY && dueMonth != null) {
            throw ApiException.validation("A monthly bill cannot have a due month",
                    Map.of("dueMonth", "only valid for YEARLY"));
        }
    }

    private Category resolveCategory(UUID userId, UUID categoryId) {
        if (categoryId == null) {
            return null;
        }
        return categoryRepository.findVisibleToUser(categoryId, userId)
                .orElseThrow(() -> ApiException.notFound("Category", categoryId));
    }

    private Bill ownedOrFail(UUID userId, UUID billId) {
        return billRepository.findOwned(billId, userId)
                .orElseThrow(() -> ApiException.notFound("Bill", billId));
    }
}
