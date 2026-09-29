package dev.pti.etl.stream;

/**
 * Published after a poll has committed (DOC-19 §6.2), never before, so listeners need no transactional binding.
 * Analytics and the UI feed (P4) subscribe on their own executors (DR-35).
 */
public record MicroBatchCommitted(StreamChunkResult result) {}
