package az.fitnest.catalog.service.impl;

import az.fitnest.catalog.client.OrderServiceGrpcClient;
import az.fitnest.catalog.dto.response.CategoryResponse;
import az.fitnest.catalog.dto.response.GymDetailResponseV3;
import az.fitnest.catalog.dto.response.GymPlanBenefitResponse;
import az.fitnest.catalog.dto.response.GymStartingPriceResponse;
import az.fitnest.catalog.dto.response.LessonTypeResponse;
import az.fitnest.catalog.dto.response.LocationResponse;
import az.fitnest.catalog.exception.ResourceNotFoundException;
import az.fitnest.catalog.model.entity.Address;
import az.fitnest.catalog.model.entity.Category;
import az.fitnest.catalog.model.entity.Gym;
import az.fitnest.catalog.model.entity.GymImage;
import az.fitnest.catalog.model.entity.GymSubscription;
import az.fitnest.catalog.model.entity.Translation;
import az.fitnest.catalog.repository.GymImageRepository;
import az.fitnest.catalog.repository.GymRepository;
import az.fitnest.catalog.repository.SavedGymRepository;
import az.fitnest.catalog.repository.TranslationRepository;
import az.fitnest.catalog.util.GeoDistance;
import az.fitnest.catalog.util.GymOpenHoursEvaluator;
import az.fitnest.order.grpc.PackageNameInfo;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Fast first-paint payload for gym detail About tab.
 * Reviews, trainers, gallery, work-hour tables and full subscription plans
 * stay on dedicated endpoints and are loaded after About.
 */
@Service
@RequiredArgsConstructor
public class GymDetailV3Service {

    private static final DateTimeFormatter OPEN_UNTIL_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final GymRepository gymRepository;
    private final SavedGymRepository savedGymRepository;
    private final GymImageRepository gymImageRepository;
    private final TranslationRepository translationRepository;
    private final OrderServiceGrpcClient orderServiceGrpcClient;
    private final Executor taskExecutor;

    @Transactional(readOnly = true)
    public GymDetailResponseV3 load(Long userId, Long gymId, Double lat, Double lng, String userLanguage) {
        Gym gym = gymRepository.findAboutDetailById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        initializeCategoryLessonTypes(gym);

        List<Long> packageIds = gym.getSubscriptions() == null
                ? List.of()
                : gym.getSubscriptions().stream()
                .map(GymSubscription::getPackageId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        CompletableFuture<Boolean> savedFuture = CompletableFuture.supplyAsync(
                () -> userId != null && savedGymRepository.existsByUserIdAndGymId(userId, gymId),
                taskExecutor
        );
        CompletableFuture<List<GymImage>> imagesFuture = CompletableFuture.supplyAsync(
                () -> gymImageRepository.findByGymId(gymId),
                taskExecutor
        );
        CompletableFuture<Map<Long, PackageNameInfo>> packagesFuture = CompletableFuture.supplyAsync(
                () -> loadPackageInfo(packageIds),
                taskExecutor
        );

        Map<String, String> translations = fetchTranslations(gym, userLanguage);
        CompletableFuture.allOf(savedFuture, imagesFuture, packagesFuture).join();

        Map<Long, PackageNameInfo> packageInfo = packagesFuture.join();
        GymSubscription startingSubscription = resolveStartingSubscription(gym.getSubscriptions(), packageInfo);
        PackageNameInfo startingPackage = startingSubscription == null
                ? null
                : packageInfo.get(startingSubscription.getPackageId());

        Address address = gym.getAddress();
        boolean isOpen = GymOpenHoursEvaluator.isOpen(
                gym.getStatus(),
                gym.getGeneralWorkHours(),
                gym.getWorkHoursMan(),
                gym.getWorkHoursWoman(),
                gym.getRestDays()
        );
        LocalTime openUntil = GymOpenHoursEvaluator.openUntil(
                gym.getStatus(),
                gym.getGeneralWorkHours(),
                gym.getWorkHoursMan(),
                gym.getWorkHoursWoman(),
                gym.getRestDays()
        );

        return GymDetailResponseV3.builder()
                .gym_id(gym.getId().toString())
                .name(firstNonBlank(translated(translations, "GYM", gym.getId(), "name"), gym.getName()))
                .description(firstNonBlank(translated(translations, "GYM", gym.getId(), "description"), gym.getDescription()))
                .address(address == null ? null : new LocationResponse(
                        address.getLatitude(),
                        address.getLongitude(),
                        firstNonBlank(translated(translations, "GYM", gym.getId(), "addressText"), address.getAddressText()),
                        firstNonBlank(translated(translations, "GYM", gym.getId(), "city"), address.getCity())
                ))
                .isSaved(savedFuture.join())
                .isOpen(isOpen)
                .openUntil(openUntil == null ? null : openUntil.format(OPEN_UNTIL_FORMAT))
                .isNew(gym.getCreatedDate() != null
                        && gym.getCreatedDate().isAfter(LocalDateTime.now().minusMonths(1L)))
                .distanceKm(address == null
                        ? null
                        : GeoDistance.roundedKm(lat, lng, address.getLatitude(), address.getLongitude()))
                .phone(gym.getPhone())
                .email(gym.getEmail())
                .categories(mapCategories(gym, translations))
                .coverImageUrl(gym.getCoverImageUrl())
                .logoUrl(resolveLogoUrl(imagesFuture.join(), gym.getCoverImageUrl()))
                .rating(gym.getRating() != null ? gym.getRating() : 0.0)
                .reviewsCount(gym.getReviewsCount() != null ? gym.getReviewsCount() : 0)
                .qr_code_url(gym.getQrCodeUrl())
                .status(gym.getStatus())
                .startingFrom(startingSubscription == null ? null : GymStartingPriceResponse.builder()
                        .planId(startingSubscription.getPackageId().toString())
                        .packageName(cleanPackageName(startingPackage != null ? startingPackage.getName() : null))
                        .dailyPrice(startingSubscription.getDailyPrice())
                        .build())
                .amenities(mapAmenities(startingSubscription, translations))
                .build();
    }

    private void initializeCategoryLessonTypes(Gym gym) {
        gym.getCategories().forEach(category -> {
            if (category.getLessonTypes() != null) {
                Hibernate.initialize(category.getLessonTypes());
            }
        });
    }

    private Map<Long, PackageNameInfo> loadPackageInfo(List<Long> packageIds) {
        if (packageIds.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return orderServiceGrpcClient.getPackageNamesByIds(packageIds).stream()
                    .collect(Collectors.toMap(PackageNameInfo::getPackageId, info -> info, (left, right) -> left));
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    private GymSubscription resolveStartingSubscription(
            Set<GymSubscription> subscriptions,
            Map<Long, PackageNameInfo> packageInfo) {
        if (subscriptions == null || subscriptions.isEmpty()) {
            return null;
        }
        return subscriptions.stream()
                .filter(sub -> sub.getPackageId() != null)
                .filter(sub -> packageInfo.containsKey(sub.getPackageId()))
                .filter(sub -> {
                    PackageNameInfo info = packageInfo.get(sub.getPackageId());
                    return info.getName() != null && info.getName().toLowerCase().contains("gold");
                })
                .findFirst()
                .or(() -> subscriptions.stream()
                        .filter(sub -> sub.getPackageId() != null)
                        .filter(sub -> packageInfo.containsKey(sub.getPackageId()))
                        .filter(sub -> sub.getDailyPrice() != null)
                        .min(Comparator.comparing(GymSubscription::getDailyPrice)))
                .or(() -> subscriptions.stream()
                        .filter(sub -> sub.getPackageId() != null)
                        .filter(sub -> packageInfo.containsKey(sub.getPackageId()))
                        .findFirst())
                .orElse(null);
    }

    private List<GymPlanBenefitResponse> mapAmenities(GymSubscription subscription, Map<String, String> translations) {
        if (subscription == null || subscription.getSupportedServices() == null) {
            return List.of();
        }
        return subscription.getSupportedServices().stream()
                .map(service -> GymPlanBenefitResponse.builder()
                        .description(firstNonBlank(
                                translated(translations, "SUPPORTEDSERVICE", service.getId(), "name"),
                                service.getName()))
                        .iconImageUrl(service.getIconUrl())
                        .build())
                .toList();
    }

    private List<CategoryResponse> mapCategories(Gym gym, Map<String, String> translations) {
        Set<Category> unique = new LinkedHashSet<>();
        if (gym.getMainCategories() != null) {
            unique.addAll(gym.getMainCategories());
        }
        if (gym.getSubCategories() != null) {
            unique.addAll(gym.getSubCategories());
        }
        return unique.stream()
                .map(category -> CategoryResponse.builder()
                        .id(category.getId())
                        .name(firstNonBlank(translated(translations, "CATEGORY", category.getId(), "name"), category.getName()))
                        .photoUrl(category.getPhotoUrl())
                        .iconUrl(category.getIconUrl())
                        .coverImageUrl(category.getPhotoUrl())
                        .lessonTypes(mapLessonTypes(category))
                        .build())
                .toList();
    }

    private List<LessonTypeResponse> mapLessonTypes(Category category) {
        if (category.getLessonTypes() == null || !Hibernate.isInitialized(category.getLessonTypes())) {
            return List.of();
        }
        return category.getLessonTypes().stream()
                .map(type -> new LessonTypeResponse(type.getId(), type.getName()))
                .toList();
    }

    private String resolveLogoUrl(List<GymImage> images, String coverImageUrl) {
        if (images == null || images.isEmpty()) {
            return coverImageUrl;
        }
        return images.stream()
                .filter(image -> matchesLogo(image.getType()) || matchesLogo(image.getImageName()))
                .map(GymImage::getUrl)
                .filter(url -> url != null && !url.isBlank())
                .findFirst()
                .orElseGet(() -> images.stream()
                        .map(GymImage::getUrl)
                        .filter(url -> url != null && !url.isBlank())
                        .findFirst()
                        .orElse(coverImageUrl));
    }

    private boolean matchesLogo(String value) {
        return value != null && "logo".equalsIgnoreCase(value.trim());
    }

    private Map<String, String> fetchTranslations(Gym gym, String userLanguage) {
        if (userLanguage == null || userLanguage.equalsIgnoreCase("AZ")) {
            return Collections.emptyMap();
        }
        String language = userLanguage.toUpperCase();
        Map<String, String> lookup = new java.util.HashMap<>();
        putTranslations(lookup, "GYM", List.of(gym.getId().toString()), language);

        List<String> categoryIds = gym.getCategories().stream()
                .map(category -> category.getId().toString())
                .distinct()
                .toList();
        putTranslations(lookup, "CATEGORY", categoryIds, language);

        List<String> serviceIds = gym.getSubscriptions() == null
                ? List.of()
                : gym.getSubscriptions().stream()
                .map(GymSubscription::getSupportedServices)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .map(service -> service.getId().toString())
                .distinct()
                .toList();
        putTranslations(lookup, "SUPPORTEDSERVICE", serviceIds, language);
        return lookup;
    }

    private void putTranslations(Map<String, String> lookup, String entityType, List<String> entityIds, String language) {
        if (entityIds == null || entityIds.isEmpty()) {
            return;
        }
        translationRepository.findByEntityTypeAndEntityIdInAndLanguageCode(entityType, entityIds, language)
                .forEach(translation -> lookup.put(translationKey(translation), translation.getFieldValue()));
    }

    private String translationKey(Translation translation) {
        return translation.getEntityType().toUpperCase() + "_" + translation.getEntityId() + "_"
                + translation.getFieldName().toLowerCase();
    }

    private String translated(Map<String, String> lookup, String entityType, Object entityId, String fieldName) {
        if (lookup == null || lookup.isEmpty() || entityId == null) {
            return null;
        }
        return lookup.get(entityType.toUpperCase() + "_" + entityId + "_" + fieldName.toLowerCase());
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private String cleanPackageName(String name) {
        if (name == null) {
            return null;
        }
        return name.replace(" Plan", "").replace(" plan", "").trim();
    }
}
