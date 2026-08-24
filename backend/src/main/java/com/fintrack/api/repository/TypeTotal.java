package com.fintrack.api.repository;

import com.fintrack.api.model.EntryType;

import java.math.BigDecimal;

/** Sum and count for one direction over a period. */
public record TypeTotal(EntryType type, BigDecimal total, long count) {}
