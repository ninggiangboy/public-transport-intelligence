package dev.pti.etl.write.domain;

import java.math.BigDecimal;

/** The original transaction a refund points at (DOC-16 §2.3): its amount and its type. */
public record OriginalSale(BigDecimal amount, String txnType) {}
