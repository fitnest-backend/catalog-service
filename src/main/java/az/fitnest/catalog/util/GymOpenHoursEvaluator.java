package az.fitnest.catalog.util;

import az.fitnest.catalog.model.entity.GymWorkHour;
import az.fitnest.catalog.model.enums.GymStatus;
import az.fitnest.catalog.model.enums.GymWorkHourPeriod;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;

public final class GymOpenHoursEvaluator {

    private static final ZoneId BAKU = ZoneId.of("Asia/Baku");

    private GymOpenHoursEvaluator() {
    }

    public static boolean isOpen(
            GymStatus status,
            Collection<GymWorkHour> generalHours,
            Collection<GymWorkHour> manHours,
            Collection<GymWorkHour> womanHours,
            Collection<GymWorkHourPeriod> restDays) {
        return isOpen(status, generalHours, manHours, womanHours, restDays, Clock.system(BAKU));
    }

    public static boolean isOpen(
            GymStatus status,
            Collection<GymWorkHour> generalHours,
            Collection<GymWorkHour> manHours,
            Collection<GymWorkHour> womanHours,
            Collection<GymWorkHourPeriod> restDays,
            Clock clock) {
        if (status != null && status != GymStatus.ACTIVE) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        DayOfWeek today = now.getDayOfWeek();
        LocalTime currentTime = now.toLocalTime();
        GymWorkHourPeriod periodToday = GymWorkHourPeriod.valueOf(today.name());

        if (restDays != null && restDays.contains(periodToday)) {
            return false;
        }

        boolean noHours = isEmpty(generalHours) && isEmpty(manHours) && isEmpty(womanHours);
        if (noHours) {
            return true;
        }

        return isTimeWithinSlots(generalHours, today, currentTime)
                || isTimeWithinSlots(manHours, today, currentTime)
                || isTimeWithinSlots(womanHours, today, currentTime);
    }

    public static LocalTime openUntil(
            GymStatus status,
            Collection<GymWorkHour> generalHours,
            Collection<GymWorkHour> manHours,
            Collection<GymWorkHour> womanHours,
            Collection<GymWorkHourPeriod> restDays) {
        return openUntil(status, generalHours, manHours, womanHours, restDays, Clock.system(BAKU));
    }

    public static LocalTime openUntil(
            GymStatus status,
            Collection<GymWorkHour> generalHours,
            Collection<GymWorkHour> manHours,
            Collection<GymWorkHour> womanHours,
            Collection<GymWorkHourPeriod> restDays,
            Clock clock) {
        if (!isOpen(status, generalHours, manHours, womanHours, restDays, clock)) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        DayOfWeek today = now.getDayOfWeek();
        LocalTime currentTime = now.toLocalTime();
        GymWorkHourPeriod periodToday = GymWorkHourPeriod.valueOf(today.name());
        GymWorkHourPeriod periodYesterday = GymWorkHourPeriod.valueOf(today.minus(1).name());

        LocalTime until = null;
        until = maxClosing(until, matchingCloseToday(generalHours, periodToday, currentTime));
        until = maxClosing(until, matchingCloseToday(manHours, periodToday, currentTime));
        until = maxClosing(until, matchingCloseToday(womanHours, periodToday, currentTime));
        until = maxClosing(until, matchingCloseOvernight(generalHours, periodYesterday, currentTime));
        until = maxClosing(until, matchingCloseOvernight(manHours, periodYesterday, currentTime));
        until = maxClosing(until, matchingCloseOvernight(womanHours, periodYesterday, currentTime));
        return until;
    }

    private static LocalTime matchingCloseToday(
            Collection<GymWorkHour> slots,
            GymWorkHourPeriod periodToday,
            LocalTime currentTime) {
        if (isEmpty(slots)) {
            return null;
        }
        return slots.stream()
                .filter(h -> h.getPeriod() == periodToday)
                .filter(h -> coversToday(h, currentTime))
                .map(GymWorkHour::getToTime)
                .filter(java.util.Objects::nonNull)
                .max(LocalTime::compareTo)
                .orElse(null);
    }

    private static LocalTime matchingCloseOvernight(
            Collection<GymWorkHour> slots,
            GymWorkHourPeriod periodYesterday,
            LocalTime currentTime) {
        if (isEmpty(slots)) {
            return null;
        }
        return slots.stream()
                .filter(h -> h.getPeriod() == periodYesterday)
                .filter(h -> coversOvernightFromYesterday(h, currentTime))
                .map(GymWorkHour::getToTime)
                .filter(java.util.Objects::nonNull)
                .max(LocalTime::compareTo)
                .orElse(null);
    }

    private static LocalTime maxClosing(LocalTime current, LocalTime candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null || candidate.isAfter(current)) {
            return candidate;
        }
        return current;
    }

    private static boolean isEmpty(Collection<GymWorkHour> hours) {
        return hours == null || hours.isEmpty();
    }

    private static boolean isTimeWithinSlots(Collection<GymWorkHour> slots, DayOfWeek today, LocalTime currentTime) {
        if (isEmpty(slots)) {
            return false;
        }

        GymWorkHourPeriod periodToday = GymWorkHourPeriod.valueOf(today.name());
        GymWorkHourPeriod periodYesterday = GymWorkHourPeriod.valueOf(today.minus(1).name());

        boolean matchesToday = slots.stream()
                .filter(h -> h.getPeriod() == periodToday)
                .anyMatch(h -> coversToday(h, currentTime));
        if (matchesToday) {
            return true;
        }

        return slots.stream()
                .filter(h -> h.getPeriod() == periodYesterday)
                .anyMatch(h -> coversOvernightFromYesterday(h, currentTime));
    }

    private static boolean coversToday(GymWorkHour hour, LocalTime currentTime) {
        LocalTime from = hour.getFromTime();
        LocalTime to = hour.getToTime();
        if (from == null && to == null) {
            return true;
        }
        if (from == null) {
            return !currentTime.isAfter(to);
        }
        if (to == null) {
            return !currentTime.isBefore(from);
        }
        if (!from.isAfter(to)) {
            return !currentTime.isBefore(from) && !currentTime.isAfter(to);
        }
        return !currentTime.isBefore(from);
    }

    private static boolean coversOvernightFromYesterday(GymWorkHour hour, LocalTime currentTime) {
        LocalTime from = hour.getFromTime();
        LocalTime to = hour.getToTime();
        return from != null && to != null && from.isAfter(to) && !currentTime.isAfter(to);
    }
}
