package dev.pti.etl.core.application.port;

import dev.pti.etl.core.domain.SalePointCdc;
import dev.pti.etl.core.domain.TicketTransactionCdc;

/**
 * Reads an unwrapped Debezium value into its typed record and payload hash: {@code DESERIALIZE} for bad bytes, DQ-01
 * otherwise (DOC-20 §4.4). The implementation lives in an adapter; the processor never sees JSON.
 */
public interface CdcReader {

    /** A CDC value that passed DQ-01, with the payload hash of DOC-09 §9. */
    record Read<T>(T value, String hash) {}

    Read<SalePointCdc> salePoint(byte[] value);

    Read<TicketTransactionCdc> ticketSale(byte[] value);
}
