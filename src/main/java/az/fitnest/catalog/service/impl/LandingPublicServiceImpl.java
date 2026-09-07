package az.fitnest.catalog.service.impl;

import az.fitnest.catalog.client.OrderServiceGrpcClient;
import az.fitnest.catalog.dto.PaginatedResponse;
import az.fitnest.catalog.dto.response.GymMainPageResponse;
import az.fitnest.catalog.dto.response.GymPlanItemResponse;
import az.fitnest.catalog.dto.response.GymSubscriptionCountResponse;
import az.fitnest.catalog.dto.response.LandingGymCardResponse;
import az.fitnest.catalog.dto.response.LandingGymDetailResponse;
import az.fitnest.catalog.dto.response.LandingStatsResponse;
import az.fitnest.catalog.dto.response.LandingStoreCardResponse;
import az.fitnest.catalog.dto.response.LandingStoreDetailResponse;
import az.fitnest.catalog.exception.ResourceNotFoundException;
import az.fitnest.catalog.mapper.GymMapper;
import az.fitnest.catalog.model.entity.Gym;
import az.fitnest.catalog.model.entity.GymImage;
import az.fitnest.catalog.model.entity.GymSubscription;
import az.fitnest.catalog.model.entity.Room;
import az.fitnest.catalog.model.entity.RoomImage;
import az.fitnest.catalog.model.entity.Store;
import az.fitnest.catalog.model.entity.StoreDiscount;
import az.fitnest.catalog.model.entity.StoreWorkHours;
import az.fitnest.catalog.model.entity.SupportedService;
import az.fitnest.catalog.model.enums.GymStatus;
import az.fitnest.catalog.model.enums.StoreStatus;
import az.fitnest.catalog.repository.GymImageRepository;
import az.fitnest.catalog.repository.GymRepository;
import az.fitnest.catalog.repository.StoreRepository;
import az.fitnest.catalog.service.FileStorageService;
import az.fitnest.catalog.service.GymReadService;
import az.fitnest.catalog.service.LandingPublicService;
import az.fitnest.catalog.service.TranslationService;
import az.fitnest.catalog.util.PublicLandingMedia;
import az.fitnest.catalog.util.UserContext;
import az.fitnest.order.grpc.SubscriptionPackageInfo;
import az.fitnest.order.grpc.SubscriptionPackageOption;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LandingPublicServiceImpl implements LandingPublicService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_PAGE = 40;
    private static final int HOME_PAGE_SIZE = 3;
    private static final int MAX_GALLERY_IMAGES = 16;
    private static final int MAX_AMENITIES = 24;
    private static final int MAX_DESCRIPTION_CHARS = 2000;
    private static final DateTimeFormatter STORE_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final GymReadService gymReadService;
    private final GymRepository gymRepository;
    private final GymImageRepository gymImageRepository;
    private final StoreRepository storeRepository;
    private final TranslationService translationService;
    private final OrderServiceGrpcClient orderServiceGrpcClient;
    private final FileStorageService fileStorageService;

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "landing-stats", key = "T(az.fitnest.catalog.util.UserContext).getUserLanguage()")
    public LandingStatsResponse getStats() {
        long gymCount = gymRepository.countByStatus(GymStatus.ACTIVE);
        long platinumGymCount = gymReadService.getGymCountBySubscription().stream()
                .filter(item -> isPlatinum(item.subscriptionName()))
                .mapToLong(GymSubscriptionCountResponse::count)
                .max()
                .orElse(0L);

        int packageCount = 0;
        int monthlyVisitLimit = 0;
        try {
            List<SubscriptionPackageInfo> packages = orderServiceGrpcClient.getGymPlans();
            List<SubscriptionPackageInfo> activePackages = packages.stream()
                    .filter(SubscriptionPackageInfo::getIsActive)
                    .toList();
            if (activePackages.isEmpty()) {
                activePackages = packages;
            }
            packageCount = activePackages.size();
            monthlyVisitLimit = resolveMonthlyVisitLimit(activePackages);
        } catch (Exception ignored) {
        }

        return LandingStatsResponse.builder()
                .gymCount(gymCount)
                .platinumGymCount(platinumGymCount)
                .monthlyVisitLimit(monthlyVisitLimit)
                .packageCount(packageCount)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "landing-home-gyms", key = "T(az.fitnest.catalog.util.UserContext).getUserLanguage()")
    public PaginatedResponse<LandingGymCardResponse> getHomeGyms() {
        return loadGymCards(1, HOME_PAGE_SIZE);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(
            value = "landing-gyms",
            key = "{#page, #pageSize, T(az.fitnest.catalog.util.UserContext).getUserLanguage()}"
    )
    public PaginatedResponse<LandingGymCardResponse> getGyms(int page, int pageSize) {
        return loadGymCards(page, pageSize);
    }

    private PaginatedResponse<LandingGymCardResponse> loadGymCards(int page, int pageSize) {
        int safePage = Math.min(Math.max(page, 1), MAX_PAGE);
        int safePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        PaginatedResponse<GymMainPageResponse> gyms = gymReadService.getGyms(
                null, null, "ALL", null, null, safePage, safePageSize, null, null, "desc");

        List<Long> ids = gyms.items().stream()
                .map(item -> parseGymId(item.gymId()))
                .filter(Objects::nonNull)
                .toList();
        Map<Long, String> phoneById = ids.isEmpty()
                ? Map.of()
                : gymRepository.findAllById(ids).stream()
                .filter(gym -> gym.getStatus() == GymStatus.ACTIVE)
                .collect(Collectors.toMap(Gym::getId, gym -> gym.getPhone() == null ? "" : gym.getPhone(), (left, right) -> left));

        List<LandingGymCardResponse> items = gyms.items().stream()
                .filter(item -> phoneById.containsKey(parseGymId(item.gymId())))
                .map(item -> toGymCard(item, phoneById.get(parseGymId(item.gymId()))))
                .toList();

        return PaginatedResponse.<LandingGymCardResponse>builder()
                .items(items)
                .total(gyms.total())
                .page(gyms.page())
                .pageSize(gyms.pageSize())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(
            value = "landing-gym-detail",
            key = "{#gymId, T(az.fitnest.catalog.util.UserContext).getUserLanguage()}"
    )
    public LandingGymDetailResponse getGym(Long gymId) {
        if (gymId == null || gymId < 1L) {
            throw new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found");
        }
        Gym gym = gymRepository.findWithDetailsById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        if (gym.getStatus() != GymStatus.ACTIVE) {
            throw new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found");
        }

        String language = UserContext.getUserLanguage();
        String localizedName = firstNonBlank(translationService.getTranslatedValue(
                "GYM", gym.getId().toString(), "name", language), gym.getName());

        String categoryName = null;
        if (gym.getCategory() != null) {
            categoryName = firstNonBlank(translationService.getTranslatedValue(
                    "CATEGORY", String.valueOf(gym.getCategory().getCategoryId()), "name", language),
                    gym.getCategory().getName());
        }

        String location = gym.getAddress() != null ? publicText(gym.getAddress().getAddressText(), 200) : null;
        String city = gym.getAddress() != null ? publicText(gym.getAddress().getCity(), 80) : null;
        Double latitude = publicLatitude(gym.getAddress() != null ? gym.getAddress().getLatitude() : null);
        Double longitude = publicLongitude(gym.getAddress() != null ? gym.getAddress().getLongitude() : null);

        String localizedDescription = firstNonBlank(translationService.getTranslatedValue(
                "GYM", gym.getId().toString(), "description", language), gym.getDescription());

        List<String> workHours = GymMapper.toWorkHoursLines(gym.getGeneralWorkHours(), language);
        List<GymImage> galleryImages = gymImageRepository.findByGymId(gymId);
        List<String> accessMemberships = resolveAccessMemberships(gym);

        return LandingGymDetailResponse.builder()
                .gymId(gym.getId().toString())
                .name(publicText(localizedName, 120))
                .coverImageUrl(PublicLandingMedia.toPublicUrl(gym.getCoverImageUrl()))
                .galleryImageUrls(toGalleryUrls(gym, galleryImages))
                .location(location)
                .city(city)
                .latitude(latitude)
                .longitude(longitude)
                .phone(publicText(gym.getPhone(), 32))
                .workHours(workHours.isEmpty() ? List.of() : workHours)
                .category(publicText(categoryName, 80))
                .membership(accessMemberships.stream()
                        .max(Comparator.comparingInt(this::membershipRank))
                        .orElse("bronze"))
                .accessMemberships(accessMemberships)
                .description(publicText(localizedDescription, MAX_DESCRIPTION_CHARS))
                .amenities(toAmenities(gym, language))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "landing-home-stores", key = "T(az.fitnest.catalog.util.UserContext).getUserLanguage()")
    public PaginatedResponse<LandingStoreCardResponse> getHomeStores() {
        return loadStoreCards(1, HOME_PAGE_SIZE);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(
            value = "landing-stores",
            key = "{#page, #pageSize, T(az.fitnest.catalog.util.UserContext).getUserLanguage()}"
    )
    public PaginatedResponse<LandingStoreCardResponse> getStores(int page, int pageSize) {
        return loadStoreCards(page, pageSize);
    }

    private PaginatedResponse<LandingStoreCardResponse> loadStoreCards(int page, int pageSize) {
        int safePage = Math.min(Math.max(page, 1), MAX_PAGE);
        int safePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Page<Store> storePage = storeRepository.findByStatusIgnoreCase(
                StoreStatus.ACTIVE.name(),
                PageRequest.of(safePage - 1, safePageSize, Sort.by(Sort.Direction.DESC, "createdDate")));

        String language = UserContext.getUserLanguage();
        List<LandingStoreCardResponse> items = storePage.getContent().stream()
                .map(store -> toStoreCard(store, language))
                .toList();

        return PaginatedResponse.<LandingStoreCardResponse>builder()
                .items(items)
                .total(storePage.getTotalElements())
                .page(safePage)
                .pageSize(safePageSize)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(
            value = "landing-store-detail",
            key = "{#storeId, T(az.fitnest.catalog.util.UserContext).getUserLanguage()}"
    )
    public LandingStoreDetailResponse getStore(Long storeId) {
        if (storeId == null || storeId < 1L) {
            throw new ResourceNotFoundException("STORE_NOT_FOUND", "error.store_not_found");
        }
        Store store = storeRepository.findByIdWithAssociations(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("STORE_NOT_FOUND", "error.store_not_found"));
        if (store.getStatus() == null || !StoreStatus.ACTIVE.name().equalsIgnoreCase(store.getStatus())) {
            throw new ResourceNotFoundException("STORE_NOT_FOUND", "error.store_not_found");
        }
        return toStoreDetail(store, UserContext.getUserLanguage());
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "landing-media-public", key = "#fileId", unless = "#result == false")
    public boolean isPublicLandingMedia(String fileId) {
        if (!PublicLandingMedia.isSafeFileId(fileId)) {
            return false;
        }
        return gymRepository.existsActivePublicCoverFile(fileId)
                || storeRepository.existsActivePublicCoverFile(fileId);
    }

    @Override
    @Transactional(readOnly = true)
    public void streamPublicLandingMedia(String fileId, java.io.OutputStream outputStream) {
        if (!isPublicLandingMedia(fileId)) {
            throw new ResourceNotFoundException("MEDIA_NOT_FOUND", "error.file_not_found");
        }
        try {
            fileStorageService.streamFileToOutput(fileId, outputStream);
        } catch (RuntimeException ex) {
            throw new ResourceNotFoundException("MEDIA_NOT_FOUND", "error.file_not_found");
        }
    }

    private LandingGymCardResponse toGymCard(GymMainPageResponse item, String phone) {
        String categoryName = item.category() != null ? item.category().name() : null;
        return LandingGymCardResponse.builder()
                .gymId(item.gymId())
                .name(publicText(item.name(), 120))
                .coverImageUrl(PublicLandingMedia.toPublicUrl(item.coverImageUrl()))
                .location(publicText(item.location(), 200))
                .city(publicText(item.city(), 80))
                .phone(publicText(phone, 32))
                .category(publicText(categoryName, 80))
                .membership(resolveMembership(item.supportedSubscriptions()))
                .build();
    }

    private LandingStoreCardResponse toStoreCard(Store store, String language) {
        String localizedName = translationService.getTranslatedValue(
                "STORE", store.getId().toString(), "name", language);
        if (localizedName == null || localizedName.isBlank()) {
            localizedName = store.getName();
        }

        String city = store.getAddress() != null ? store.getAddress().getCity() : null;
        String addressText = store.getAddress() != null ? store.getAddress().getAddressText() : null;
        if (!"AZ".equalsIgnoreCase(language)) {
            String translatedCity = translationService.getTranslatedValue(
                    "STORE", store.getId().toString(), "city", language);
            String translatedAddress = translationService.getTranslatedValue(
                    "STORE", store.getId().toString(), "addressText", language);
            if (translatedCity != null && !translatedCity.isBlank()) {
                city = translatedCity;
            }
            if (translatedAddress != null && !translatedAddress.isBlank()) {
                addressText = translatedAddress;
            }
        }

        List<String> discounts = store.getDiscounts() == null
                ? List.of()
                : store.getDiscounts().stream()
                .map(StoreDiscount::getPercent)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .map(percent -> percent + "%")
                .toList();

        boolean isNew = store.getCreatedDate() != null
                && store.getCreatedDate().isAfter(LocalDateTime.now().minusDays(30));

        return LandingStoreCardResponse.builder()
                .storeId(store.getId())
                .name(publicText(localizedName, 120))
                .coverImageUrl(PublicLandingMedia.toPublicUrl(store.getCoverImageUrl()))
                .city(publicText(city, 80))
                .addressText(publicText(addressText, 200))
                .category(publicText(store.getCategory(), 80))
                .discounts(discounts)
                .isNew(isNew)
                .phone(publicText(store.getPhone(), 32))
                .workHoursText(toWorkHoursText(store.getWorkHours()))
                .build();
    }

    private LandingStoreDetailResponse toStoreDetail(Store store, String language) {
        LandingStoreCardResponse card = toStoreCard(store, language);
        return LandingStoreDetailResponse.builder()
                .storeId(card.storeId())
                .name(card.name())
                .coverImageUrl(card.coverImageUrl())
                .city(card.city())
                .addressText(card.addressText())
                .category(card.category())
                .phone(card.phone())
                .email(publicText(store.getEmail(), 120))
                .workHoursText(card.workHoursText())
                .discounts(card.discounts())
                .isNew(card.isNew())
                .build();
    }

    private String toWorkHoursText(StoreWorkHours workHours) {
        if (workHours == null || workHours.getFromTime() == null || workHours.getToTime() == null) {
            return null;
        }
        return workHours.getFromTime().format(STORE_TIME_FMT)
                + " - "
                + workHours.getToTime().format(STORE_TIME_FMT);
    }

    private String resolveMembership(List<GymPlanItemResponse> subscriptions) {
        if (subscriptions == null || subscriptions.isEmpty()) {
            return "bronze";
        }
        return subscriptions.stream()
                .map(GymPlanItemResponse::packageName)
                .map(this::membershipFromName)
                .max(Comparator.comparingInt(this::membershipRank))
                .orElse("bronze");
    }

    private List<String> resolveAccessMemberships(Gym gym) {
        if (gym.getSubscriptions() == null || gym.getSubscriptions().isEmpty()) {
            return List.of("bronze");
        }
        List<Long> packageIds = gym.getSubscriptions().stream()
                .map(GymSubscription::getPackageId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (packageIds.isEmpty()) {
            return List.of("bronze");
        }
        try {
            List<String> tiers = orderServiceGrpcClient.getPackageNamesByIds(packageIds).stream()
                    .map(az.fitnest.order.grpc.PackageNameInfo::getName)
                    .map(this::membershipFromName)
                    .distinct()
                    .sorted(Comparator.comparingInt(this::membershipRank))
                    .toList();
            return tiers.isEmpty() ? List.of("bronze") : tiers;
        } catch (Exception ignored) {
            return List.of("bronze");
        }
    }

    private List<String> toAmenities(Gym gym, String language) {
        if (gym.getSubscriptions() == null || gym.getSubscriptions().isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (GymSubscription subscription : gym.getSubscriptions()) {
            Set<SupportedService> services = subscription.getSupportedServices();
            if (services == null) {
                continue;
            }
            for (SupportedService service : services) {
                if (service == null) {
                    continue;
                }
                String localized = translationService.getTranslatedValue(
                        "SUPPORTEDSERVICE",
                        String.valueOf(service.getId()),
                        "name",
                        language);
                String name = (localized != null && !localized.isBlank()) ? localized : service.getName();
                if (name != null && !name.isBlank()) {
                    names.add(name.trim());
                }
            }
        }
        return names.stream().limit(MAX_AMENITIES).toList();
    }

    private List<String> toGalleryUrls(Gym gym, List<GymImage> images) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        addPublicUrl(urls, gym.getCoverImageUrl());
        if (images != null) {
            for (GymImage image : images) {
                if (image == null || isLogoImage(image)) {
                    continue;
                }
                addPublicUrl(urls, image.getUrl());
            }
        }
        if (gym.getRooms() != null) {
            for (Room room : gym.getRooms()) {
                if (room == null || room.getImages() == null) {
                    continue;
                }
                for (RoomImage roomImage : room.getImages()) {
                    if (roomImage == null) {
                        continue;
                    }
                    addPublicUrl(urls, roomImage.getPictureUrl());
                }
            }
        }
        return urls.stream().limit(MAX_GALLERY_IMAGES).toList();
    }

    private void addPublicUrl(LinkedHashSet<String> urls, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String publicUrl = PublicLandingMedia.toPublicUrl(raw.trim());
        if (publicUrl != null && !publicUrl.isBlank()) {
            urls.add(publicUrl);
        }
    }

    private boolean isLogoImage(GymImage image) {
        return matchesLogo(image.getType()) || matchesLogo(image.getImageName()) || matchesLogo(image.getTitle());
    }

    private boolean matchesLogo(String value) {
        return value != null && "logo".equalsIgnoreCase(value.trim());
    }

    private String membershipFromName(String name) {
        if (name == null) {
            return "bronze";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("platinum") || lower.contains("platin")) {
            return "platinum";
        }
        if (lower.contains("gold") || lower.contains("qızıl") || lower.contains("qizil")) {
            return "gold";
        }
        if (lower.contains("silver") || lower.contains("gümüş") || lower.contains("gumus")) {
            return "silver";
        }
        return "bronze";
    }

    private int membershipRank(String membership) {
        return switch (membership) {
            case "platinum" -> 4;
            case "gold" -> 3;
            case "silver" -> 2;
            default -> 1;
        };
    }

    private boolean isPlatinum(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("platinum") || lower.contains("platin");
    }

    private int resolveMonthlyVisitLimit(List<SubscriptionPackageInfo> packages) {
        SubscriptionPackageInfo bronze = packages.stream()
                .filter(pkg -> isBronze(pkg.getName()))
                .findFirst()
                .orElse(packages.isEmpty() ? null : packages.get(0));
        if (bronze == null) {
            return 0;
        }
        return bronze.getOptionsList().stream()
                .filter(option -> option.getDurationMonths() == 1)
                .mapToInt(SubscriptionPackageOption::getEntryLimit)
                .findFirst()
                .orElseGet(() -> bronze.getOptionsList().stream()
                        .mapToInt(SubscriptionPackageOption::getEntryLimit)
                        .findFirst()
                        .orElse(0));
    }

    private boolean isBronze(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("bronze") || lower.contains("bürünc") || lower.contains("burunc");
    }

    private Long parseGymId(String gymId) {
        if (gymId == null || gymId.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(gymId);
            return parsed < 1L ? null : parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }

    private String publicText(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > maxChars ? trimmed.substring(0, maxChars) : trimmed;
    }

    private Double publicLatitude(Double latitude) {
        if (latitude == null || latitude.isNaN() || latitude.isInfinite() || latitude < -90d || latitude > 90d) {
            return null;
        }
        return latitude;
    }

    private Double publicLongitude(Double longitude) {
        if (longitude == null || longitude.isNaN() || longitude.isInfinite() || longitude < -180d || longitude > 180d) {
            return null;
        }
        return longitude;
    }
}
