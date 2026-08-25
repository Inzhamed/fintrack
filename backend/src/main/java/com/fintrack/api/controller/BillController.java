package com.fintrack.api.controller;

import com.fintrack.api.dto.bill.BillResponse;
import com.fintrack.api.dto.bill.CreateBillRequest;
import com.fintrack.api.dto.bill.UpdateBillRequest;
import com.fintrack.api.security.AuthenticatedUser;
import com.fintrack.api.service.BillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bills")
@RequiredArgsConstructor
@Tag(name = "Bills", description = "Recurring payments and their reminders")
public class BillController {

    private final BillService billService;

    @GetMapping
    @Operation(summary = "List bills, active first",
            description = "Each carries its next due date, computed from the recurrence rule.")
    public List<BillResponse> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return billService.list(principal.id(), LocalDate.now());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a recurring bill",
            description = "A yearly bill needs a due month; a monthly one must not have one. "
                    + "A due day past the end of a short month is clamped to its last day.")
    public BillResponse create(@AuthenticationPrincipal AuthenticatedUser principal,
                               @Valid @RequestBody CreateBillRequest request) {
        return billService.create(principal.id(), request, LocalDate.now());
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a bill",
            description = "Recurrence is immutable. Setting active=false stops its reminders "
                    + "without losing the bill.")
    public BillResponse update(@AuthenticationPrincipal AuthenticatedUser principal,
                               @PathVariable UUID id,
                               @Valid @RequestBody UpdateBillRequest request) {
        return billService.update(principal.id(), id, request, LocalDate.now());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a bill")
    public void delete(@AuthenticationPrincipal AuthenticatedUser principal,
                       @PathVariable UUID id) {
        billService.delete(principal.id(), id);
    }
}
