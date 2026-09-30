package dev.pti.analytics.otp.application;

import dev.pti.analytics.core.domain.Trigger;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One service date of an OTP run.
 *
 * @param batchId the {@code batch_id} written to the rows: the step's (DR-63)
 * @param trigger {@code JOB} for the scheduled or requested job, {@code RECOMPUTE} for a replay
 */
public record OtpDayRun(LocalDate serviceDate, UUID batchId, Trigger trigger) {}
