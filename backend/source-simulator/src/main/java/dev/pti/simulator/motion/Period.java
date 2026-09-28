package dev.pti.simulator.motion;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/** Peak hours are 06:00–09:00 and 15:00–18:30 on weekdays, agency time (DOC-25 §5.3). */
public enum Period {
    PEAK,
    OFF_PEAK;

    private static final LocalTime MORNING_START = LocalTime.of(6, 0);
    private static final LocalTime MORNING_END = LocalTime.of(9, 0);
    private static final LocalTime EVENING_START = LocalTime.of(15, 0);
    private static final LocalTime EVENING_END = LocalTime.of(18, 30);

    public static Period at(ZonedDateTime local) {
        DayOfWeek day = local.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return OFF_PEAK;
        }
        LocalTime time = local.toLocalTime();
        boolean morning = !time.isBefore(MORNING_START) && time.isBefore(MORNING_END);
        boolean evening = !time.isBefore(EVENING_START) && time.isBefore(EVENING_END);
        return morning || evening ? PEAK : OFF_PEAK;
    }
}
