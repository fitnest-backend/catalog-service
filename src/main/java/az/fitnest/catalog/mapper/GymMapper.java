package az.fitnest.catalog.mapper;

import az.fitnest.catalog.dto.*;
import az.fitnest.catalog.dto.request.*;
import az.fitnest.catalog.dto.response.*;
import az.fitnest.catalog.dto.response.CategoryResponse;
import az.fitnest.catalog.dto.response.ProfessionResponse;
import az.fitnest.catalog.model.entity.*;

import java.util.List;

public final class GymMapper {

    private static final java.util.Map<String, java.util.Set<az.fitnest.catalog.model.enums.GymWorkHourPeriod>> PERIOD_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private GymMapper() {
    }

    public static java.util.Set<GymWorkHour> toWorkHours(java.util.Set<GymWorkHourResponse> dtos) {
        if (dtos == null) return new java.util.HashSet<>();
        return dtos.stream()
                .flatMap(dto -> {
                    if (dto.period() == null) throw new az.fitnest.catalog.exception.BadRequestException("INVALID_PERIOD", "error.invalid_period");
                    return expandPeriods(dto.period()).stream()
                            .map(p -> new GymWorkHour(p, dto.from(), dto.to()));
                })
                .collect(java.util.stream.Collectors.toSet());
    }

    public static java.util.Set<az.fitnest.catalog.model.enums.GymWorkHourPeriod> expandPeriods(String periodStr) {
        if (periodStr == null || periodStr.isBlank()) return java.util.Collections.emptySet();

        String upper = periodStr.toUpperCase().trim();
        return PERIOD_CACHE.computeIfAbsent(upper, k -> {
            if (k.contains("-")) {
                String[] parts = k.split("-");
                if (parts.length == 2) {
                    try {
                        az.fitnest.catalog.model.enums.GymWorkHourPeriod start = az.fitnest.catalog.model.enums.GymWorkHourPeriod.valueOf(parts[0].trim());
                        az.fitnest.catalog.model.enums.GymWorkHourPeriod end = az.fitnest.catalog.model.enums.GymWorkHourPeriod.valueOf(parts[1].trim());

                        java.util.Set<az.fitnest.catalog.model.enums.GymWorkHourPeriod> result = new java.util.HashSet<>();
                        int startIdx = start.ordinal();
                        int endIdx = end.ordinal();

                        if (startIdx <= endIdx) {
                            for (int i = startIdx; i <= endIdx; i++) {
                                result.add(az.fitnest.catalog.model.enums.GymWorkHourPeriod.values()[i]);
                            }
                        } else {
                            for (int i = startIdx; i < 7; i++) {
                                result.add(az.fitnest.catalog.model.enums.GymWorkHourPeriod.values()[i]);
                            }
                            for (int i = 0; i <= endIdx; i++) {
                                result.add(az.fitnest.catalog.model.enums.GymWorkHourPeriod.values()[i]);
                            }
                        }
                        return java.util.Collections.unmodifiableSet(result);
                    } catch (Exception e) {
                        throw new az.fitnest.catalog.exception.BadRequestException("INVALID_PERIOD_RANGE", "error.invalid_period_range");
                    }
                }
            }

            try {
                return java.util.Set.of(az.fitnest.catalog.model.enums.GymWorkHourPeriod.valueOf(k));
            } catch (IllegalArgumentException e) {
                throw new az.fitnest.catalog.exception.BadRequestException("INVALID_PERIOD", "error.invalid_period");
            }
        });
    }

    public static GymAdmin toAdminEntity(Gym gym, GymAdminCreateRequest adminReq, Long userId, String role) {
        GymAdmin admin = new GymAdmin();
        admin.setGym(gym);
        admin.setUserId(userId);
        admin.setName(adminReq.name());
        admin.setSurname(adminReq.surname());
        admin.setPhoneNumber(az.fitnest.catalog.util.PhoneUtil.normalize(adminReq.phoneNumber()));
        admin.setEmail(adminReq.email());
        admin.setRole(role);
        return admin;
    }

    public static GymTrainerResponse toTrainerDto(Trainer t) {
        if (t == null) return null;
        ProfessionResponse professionDto = null;
        if (t.getProfession() != null) {
            professionDto = ProfessionResponse.builder()
                    .id(t.getProfession().getId())
                    .name(t.getProfession().getName())
                    .build();
        }

        return GymTrainerResponse.builder()
                .trainer_id(t.getId() != null ? t.getId().toString() : null)
                .name(t.getName())
                .surname(t.getSurname())
                .profession(professionDto)
                .picture(t.getPicture())
                .phone(t.getPhone())
                .email(t.getEmail())
                .build();
    }

    public static GymReviewResponse toReviewDto(Review r, String status) {
        if (r == null) return null;
        return new GymReviewResponse(
                r.getId(),
                r.getId() != null ? r.getId().toString() : null,
                r.getRating(),
                r.getComment(),
                GymReviewAuthorResponse.builder()
                        .user_id(r.getUserId() != null ? r.getUserId().toString() : null)
                        .full_name("User " + r.getUserId())
                        .build(),
                status,
                r.getCreatedDate() != null ? r.getCreatedDate().toLocalDate() : null
        );
    }

    public static GymReviewResponse toReviewDto(Review r, String fullName, String profileImageUrl, String status) {
        if (r == null) return null;
        return new GymReviewResponse(
                r.getId(),
                r.getId() != null ? r.getId().toString() : null,
                r.getRating(),
                r.getComment(),
                GymReviewAuthorResponse.builder()
                        .user_id(r.getUserId() != null ? r.getUserId().toString() : null)
                        .full_name(fullName)
                        .avatar_url(profileImageUrl)
                        .build(),
                status,
                r.getCreatedDate() != null ? r.getCreatedDate().toLocalDate() : null
        );
    }

    public static GymImageDto toImageDto(GymImage img) {
        if (img == null) return null;
        return GymImageDto.builder()
                .id(img.getId())
                .gymId(img.getGym() != null ? img.getGym().getId() : null)
                .name(img.getImageName())
                .url(img.getUrl())
                .build();
    }

    public static GymImageItemResponse toImageItemDto(GymImage img) {
        if (img == null) return null;
        return GymImageItemResponse.builder()
                .image_id(img.getId() != null ? img.getId().toString() : "img_" + System.identityHashCode(img))
                .url(img.getUrl())
                .type(img.getType() != null ? img.getType() : "other")
                .title(img.getTitle())
                .build();
    }

    private static final java.util.Map<az.fitnest.catalog.model.enums.GymWorkHourPeriod, String> AZ_DAYS = java.util.Map.of(
            az.fitnest.catalog.model.enums.GymWorkHourPeriod.MONDAY, "Bazar ertəsi",
            az.fitnest.catalog.model.enums.GymWorkHourPeriod.TUESDAY, "Çərşənbə axşamı",
            az.fitnest.catalog.model.enums.GymWorkHourPeriod.WEDNESDAY, "Çərşənbə",
            az.fitnest.catalog.model.enums.GymWorkHourPeriod.THURSDAY, "Cümə axşamı",
            az.fitnest.catalog.model.enums.GymWorkHourPeriod.FRIDAY, "Cümə",
            az.fitnest.catalog.model.enums.GymWorkHourPeriod.SATURDAY, "Şənbə",
            az.fitnest.catalog.model.enums.GymWorkHourPeriod.SUNDAY, "Bazar"
    );

    private static final java.util.Map<az.fitnest.catalog.model.enums.GymWorkHourPeriod, String> EN_DAYS = java.util.Map.of(
            az.fitnest.catalog.model.enums.GymWorkHourPeriod.MONDAY, "Monday",
            az.fitnest.catalog.model.enums.GymWorkHourPeriod.TUESDAY, "Tuesday",
            az.fitnest.catalog.model.enums.GymWorkHourPeriod.WEDNESDAY, "Wednesday",
            az.fitnest.catalog.model.enums.GymWorkHourPeriod.THURSDAY, "Thursday",
            az.fitnest.catalog.model.enums.GymWorkHourPeriod.FRIDAY, "Friday",
            az.fitnest.catalog.model.enums.GymWorkHourPeriod.SATURDAY, "Saturday",
            az.fitnest.catalog.model.enums.GymWorkHourPeriod.SUNDAY, "Sunday"
    );

    private static final java.util.Map<az.fitnest.catalog.model.enums.GymWorkHourPeriod, String> RU_DAYS = java.util.Map.of(
            az.fitnest.catalog.model.enums.GymWorkHourPeriod.MONDAY, "Понедельник",
            az.fitnest.catalog.model.enums.GymWorkHourPeriod.TUESDAY, "Вторник",
            az.fitnest.catalog.model.enums.GymWorkHourPeriod.WEDNESDAY, "Среда",
            az.fitnest.catalog.model.enums.GymWorkHourPeriod.THURSDAY, "Четверг",
            az.fitnest.catalog.model.enums.GymWorkHourPeriod.FRIDAY, "Пятница",
            az.fitnest.catalog.model.enums.GymWorkHourPeriod.SATURDAY, "Суббота",
            az.fitnest.catalog.model.enums.GymWorkHourPeriod.SUNDAY, "Воскресенье"
    );

    public static String getLocalizedDay(az.fitnest.catalog.model.enums.GymWorkHourPeriod period, String lang) {
        if (period == null) return null;
        if (lang != null && lang.equalsIgnoreCase("RU")) return RU_DAYS.getOrDefault(period, period.name());
        if (lang != null && lang.equalsIgnoreCase("EN")) return EN_DAYS.getOrDefault(period, period.name());
        return AZ_DAYS.getOrDefault(period, period.name());
    }

    public static List<GymWorkHourResponse> toGroupedWorkHourDtos(java.util.Collection<GymWorkHour> workHours, String lang) {
        if (workHours == null || workHours.isEmpty()) return java.util.Collections.emptyList();

        List<GymWorkHour> sorted = workHours.stream()
                .sorted(java.util.Comparator.comparingInt(wh -> wh.getPeriod().ordinal()))
                .toList();

        List<GymWorkHourResponse> grouped = new java.util.ArrayList<>();
        int i = 0;
        while (i < sorted.size()) {
            GymWorkHour start = sorted.get(i);
            int j = i + 1;
            while (j < sorted.size() &&
                    sorted.get(j).getPeriod().ordinal() == sorted.get(j - 1).getPeriod().ordinal() + 1 &&
                    java.util.Objects.equals(sorted.get(j).getFromTime(), start.getFromTime()) &&
                    java.util.Objects.equals(sorted.get(j).getToTime(), start.getToTime())) {
                j++;
            }

            String periodStr;
            if (j - i > 1) {
                periodStr = getLocalizedDay(start.getPeriod(), lang) + " – " + getLocalizedDay(sorted.get(j - 1).getPeriod(), lang);
            } else {
                periodStr = getLocalizedDay(start.getPeriod(), lang);
            }

            grouped.add(GymWorkHourResponse.builder()
                    .period(periodStr)
                    .from(start.getFromTime())
                    .to(start.getToTime())
                    .build());
            i = j;
        }

        return grouped;
    }

    public static GymWorkHourResponse toWorkHourDto(GymWorkHour wh, String lang) {
        if (wh == null) return null;
        return GymWorkHourResponse.builder()
                .period(getLocalizedDay(wh.getPeriod(), lang))
                .from(wh.getFromTime())
                .to(wh.getToTime())
                .build();
    }

    public static CategoryResponse toCategoryResponse(Category category) {
        if (category == null) return null;
        return CategoryResponse.builder()
                .id(category.getCategoryId())
                .name(category.getName())
                .photoUrl(category.getPhotoUrl())
                .iconUrl(category.getIconUrl())
                .coverImageUrl(category.getPhotoUrl())
                .build();
    }

    public static List<String> toWorkHoursLines(java.util.Collection<GymWorkHour> workHours, String lang) {
        if (workHours == null || workHours.isEmpty()) {
            return List.of();
        }
        return toGroupedWorkHourDtos(workHours, lang).stream()
                .map(GymMapper::formatWorkHour)
                .filter(line -> line != null && !line.isBlank())
                .toList();
    }

    public static String toWorkHoursText(java.util.Collection<GymWorkHour> workHours, String lang) {
        List<String> lines = toWorkHoursLines(workHours, lang);
        return lines.isEmpty() ? null : String.join(" | ", lines);
    }

    private static String formatWorkHour(GymWorkHourResponse dto) {
        if (dto == null) {
            return null;
        }
        String fromTime = dto.from() != null ? dto.from().toString() : "";
        String toTime = dto.to() != null ? dto.to().toString() : "";
        if (!fromTime.isBlank() && !toTime.isBlank()) {
            return dto.period() + ": " + fromTime + " - " + toTime;
        }
        return dto.period();
    }
}
