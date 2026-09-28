package dev.pti.spike.batch;

/** A value below zero violates the CHECK constraint of spike_fact (SQLState 23514). */
public record Item(int id, int value) {}
