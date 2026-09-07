package az.fitnest.catalog.service.impl;

import az.fitnest.catalog.client.OrderServiceGrpcClient;
import az.fitnest.catalog.dto.PaginatedResponse;
import az.fitnest.catalog.dto.response.GymMainPageResponse;
import az.fitnest.catalog.dto.response.GymPlanItemResponse;
import az.fitnest.catalog.dto.response.GymSubscriptionCountResponse;
import az.fitnest.catalog.dto.response.LandingGymResponse;
import az.fitnest.catalog.dto.response.LandingStatsResponse;
import az.fitnest.catalog.dto.response.LandingStoreResponse;
import az.fitnest.catalog.exception.ResourceNotFoundException;
import az.fitnest.catalog.mapper.GymMapper;
import az.fitnest.catalog.model.entity.Gym;
import az.fitnest.catalog.model.entity.Store;
import az.fitnest.catalog.model.entity.StoreDiscount;
import az.fitnest.catalog.model.enums.GymStatus;
import az.fitnest.catalog.model.enums.StoreStatus;
import az.fitnest.catalog.repository.GymRepository;
import az.fitnest.catalog.repository.StoreRepository;
import az.fitnest.catalog.service.GymReadService;
import az.fitnest.catalog.service.LandingPublicService;
import az.fitnest.catalog.service.TranslationService;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LandingPublicServiceImpl implements LandingPublicService {

    private static final int MAX_PAGE_SIZE = 100;

    private final GymReadService gymReadService;
    private final GymRepository gymRepository;
    private final StoreRepository storeRepository;
    private final TranslationService translationService;
    private final OrderServiceGrpcClient orderServiceGrpcClient;

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
    @Cacheable(
            value = "landing-gyms",
            key = "{#page, #pageSize, T(az.fitnest.catalog.util.UserContext).getUserLanguage()}"
    )
    public PaginatedResponse<LandingGymResponse> getGyms(int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        PaginatedResponse<GymMainPageResponse> gyms = gymReadService.getGyms(
                null, null, "ALL", null, null, safePage, safePageSize, null, null, "desc");

        List<Long> ids = gyms.items().stream()
                .map(item -> parseGymId(item.gymId()))
                .filter(Objects::nonNull)
                .toList();
        Map<Long, Gym> gymMap = ids.isEmpty()
                ? Map.of()
                : gymRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Gym::getId, gym -> gym, (left, right) -> left));

        List<LandingGymResponse> items = gyms.items().stream()
                .map(item -> toLandingGym(item, gymMap.get(parseGymId(item.gymId()))))
                .toList();

        return PaginatedResponse.<LandingGymResponse>builder()
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
    public LandingGymResponse getGym(Long gymId) {
        Gym gym = gymRepository.findWithDetailsById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        if (gym.getStatus() != GymStatus.ACTIVE) {
            throw new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found");
        }

        String language = UserContext.getUserLanguage();
        String localizedName = translationService.getTranslatedValue(
                "GYM", gym.getId().toString(), "name", language);
        if (localizedName == null || localizedName.isBlank()) {
            localizedName = gym.getName();
        }

        String categoryName = null;
        if (gym.getCategory() != null) {
            String localizedCategory = translationService.getTranslatedValue(
                    "CATEGORY", String.valueOf(gym.getCategory().getCategoryId()), "name", language);
            categoryName = (localizedCategory != null && !localizedCategory.isBlank())
                    ? localizedCategory
                    : gym.getCategory().getName();
        }

        String location = gym.getAddress() != null ? gym.getAddress().getAddressText() : null;
        String city = gym.getAddress() != null ? gym.getAddress().getCity() : null;

        return LandingGymResponse.builder()
                .gymId(gym.getId().toString())
                .name(localizedName)
                .coverImageUrl(gym.getCoverImageUrl())
                .location(location)
                .city(city)
                .phone(gym.getPhone())
                .email(gym.getEmail())
                .workHoursText(GymMapper.toWorkHoursText(gym.getGeneralWorkHours(), language))
                .category(categoryName)
                .membership(resolveMembershipFromGym(gym))
                .description(gym.getDescription())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(
            value = "landing-stores",
            key = "{#page, #pageSize, T(az.fitnest.catalog.util.UserContext).getUserLanguage()}"
    )
    public PaginatedResponse<LandingStoreResponse> getStores(int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Page<Store> storePage = storeRepository.findByStatusIgnoreCase(
                StoreStatus.ACTIVE.name(),
                PageRequest.of(safePage - 1, safePageSize, Sort.by(Sort.Direction.DESC, "createdDate")));

        String language = UserContext.getUserLanguage();
        List<LandingStoreResponse> items = storePage.getContent().stream()
                .map(store -> toLandingStore(store, language))
                .toList();

        return PaginatedResponse.<LandingStoreResponse>builder()
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
    public LandingStoreResponse getStore(Long storeId) {
        Store store = storeRepository.findByIdWithAssociations(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("STORE_NOT_FOUND", "error.store_not_found"));
        if (store.getStatus() == null || !StoreStatus.ACTIVE.name().equalsIgnoreCase(store.getStatus())) {
            throw new ResourceNotFoundException("STORE_NOT_FOUND", "error.store_not_found");
        }
        return toLandingStore(store, UserContext.getUserLanguage());
    }

    private LandingGymResponse toLandingGym(GymMainPageResponse item, Gym gym) {
        return LandingGymResponse.builder()
                .gymId(item.gymId())
                .name(item.name())
                .coverImageUrl(item.coverImageUrl())
                .location(item.location())
                .city(item.city())
                .phone(gym != null ? gym.getPhone() : null)
                .email(gym != null ? gym.getEmail() : null)
                .workHoursText(item.workHoursText())
                .category(item.category() != null ? item.category().name() : null)
                .membership(resolveMembership(item.supportedSubscriptions()))
                .description(gym != null ? gym.getDescription() : null)
                .build();
    }

    private LandingStoreResponse toLandingStore(Store store, String language) {
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

        return LandingStoreResponse.builder()
                .storeId(store.getId())
                .name(localizedName)
                .coverImageUrl(store.getCoverImageUrl())
                .city(city)
                .addressText(addressText)
                .category(store.getCategory())
                .discounts(discounts)
                .isNew(isNew)
                .phone(store.getPhone())
                .email(store.getEmail())
                .build();
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

    private String resolveMembershipFromGym(Gym gym) {
        if (gym.getSubscriptions() == null || gym.getSubscriptions().isEmpty()) {
            return "bronze";
        }
        List<Long> packageIds = gym.getSubscriptions().stream()
                .map(sub -> sub.getPackageId())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (packageIds.isEmpty()) {
            return "bronze";
        }
        try {
            return orderServiceGrpcClient.getPackageNamesByIds(packageIds).stream()
                    .map(az.fitnest.order.grpc.PackageNameInfo::getName)
                    .map(this::membershipFromName)
                    .max(Comparator.comparingInt(this::membershipRank))
                    .orElse("bronze");
        } catch (Exception ignored) {
            return "bronze";
        }
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
            return Long.parseLong(gymId);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
