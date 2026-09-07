package az.fitnest.catalog.service.impl;

import az.fitnest.catalog.model.entity.Category;
import az.fitnest.catalog.model.entity.GymDescription;
import az.fitnest.catalog.model.entity.RoomImage;

import az.fitnest.catalog.dto.*;
import az.fitnest.catalog.dto.request.*;
import az.fitnest.catalog.dto.response.*;
import az.fitnest.catalog.dto.response.CategoryResponse;
import az.fitnest.catalog.dto.response.ProfessionResponse;
import az.fitnest.catalog.mapper.GymMapper;
import az.fitnest.catalog.exception.ResourceNotFoundException;
import az.fitnest.catalog.exception.BadRequestException;
import az.fitnest.catalog.exception.ForbiddenException;
import az.fitnest.catalog.exception.UnauthorizedException;
import az.fitnest.catalog.model.entity.Address;
import az.fitnest.catalog.model.entity.Gym;
import az.fitnest.catalog.model.entity.SavedGym;
import az.fitnest.catalog.model.entity.GymEntranceHistory;
import az.fitnest.catalog.repository.GymEntranceHistoryRepository;
import az.fitnest.catalog.repository.GymImageRepository;
import az.fitnest.catalog.repository.GymRepository;
import az.fitnest.catalog.repository.ReviewRepository;
import az.fitnest.catalog.repository.TrainerReservationDateRepository;
import az.fitnest.catalog.repository.TrainerRepository;
import az.fitnest.catalog.repository.CategoryRepository;
import az.fitnest.catalog.repository.ReservationRepository;
import az.fitnest.catalog.repository.TranslationRepository;
import az.fitnest.catalog.repository.GymDescriptionRepository;
import az.fitnest.catalog.service.TranslationService;
import az.fitnest.catalog.util.PlatformUtil;
import az.fitnest.catalog.util.UserContext;
import az.fitnest.catalog.client.OrderServiceGrpcClient;
import az.fitnest.catalog.client.UserServiceGrpcClient;
import az.fitnest.catalog.model.entity.Reservation;
import az.fitnest.catalog.model.enums.ReservationStatus;
import az.fitnest.catalog.client.CachedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GymReadServiceImpl implements az.fitnest.catalog.service.GymReadService {
    private final GymRepository gymRepository;
    private final GymDescriptionRepository gymDescriptionRepository;
    private final az.fitnest.catalog.repository.SavedGymRepository savedGymRepository;
    private final GymImageRepository gymImageRepository;
    private final TrainerRepository trainerRepository;
    private final ReviewRepository reviewRepository;
    private final TrainerReservationDateRepository trainerReservationDateRepository;
    private final ReservationRepository reservationRepository;
    private final OrderServiceGrpcClient orderServiceGrpcClient;
    private final UserServiceGrpcClient userServiceGrpcClient;
    private final org.springframework.context.MessageSource messageSource;
    private final CategoryRepository categoryRepository;
    private final TranslationService translationService;
    private final TranslationRepository translationRepository;
    private final GymEntranceHistoryRepository gymEntranceHistoryRepository;
    private final az.fitnest.catalog.repository.SupportedServiceRepository supportedServiceRepository;
    private final az.fitnest.catalog.repository.GymAdminRepository gymAdminRepository;
    private final az.fitnest.catalog.service.GymQrCodeService gymQrCodeService;
    private final az.fitnest.catalog.client.StorageGrpcClient storageGrpcClient;
    private final java.util.concurrent.Executor taskExecutor;
    private final az.fitnest.catalog.service.GymEntranceService gymEntranceService;
    private final GymDetailV3Service gymDetailV3Service;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private GymReadServiceImpl self;

    private final com.github.benmanes.caffeine.cache.Cache<Long, List<Long>> eligibleSubscriptionIdsCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(java.time.Duration.ofMinutes(30))
            .build();
    private final com.github.benmanes.caffeine.cache.Cache<Long, az.fitnest.order.grpc.PackageNameInfo> packageInfoCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(java.time.Duration.ofHours(2))
            .build();

    private String resolveUserLanguage() {
        Long userId = UserContext.getCurrentUserId();
        return getUserLanguage(userId);
    }

    public List<az.fitnest.catalog.dto.response.SupportedServiceResponse> getAllSupportedServices(Long gymId) {
        List<az.fitnest.catalog.model.entity.SupportedService> services = gymId == null
                ? supportedServiceRepository.findAllByGymIdIsNull()
                : supportedServiceRepository.findAllByGymId(gymId);
        String userLanguage = resolveUserLanguage();
        return services.stream()
                .map(s -> {
                    String localizedName = translationService.getTranslatedValue(
                            "SUPPORTEDSERVICE", s.getId().toString(), "name", userLanguage);
                    String name = (localizedName != null && !localizedName.isEmpty()) ? localizedName : s.getName();
                    return new az.fitnest.catalog.dto.response.SupportedServiceResponse(s.getId(), name, s.getGymId(), s.getIconUrl());
                })
                .toList();
    }

    public String getUserLanguage(Long userId) {
        try {
            var requestAttributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (requestAttributes instanceof org.springframework.web.context.request.ServletRequestAttributes) {
                jakarta.servlet.http.HttpServletRequest request = ((org.springframework.web.context.request.ServletRequestAttributes) requestAttributes).getRequest();
                String acceptLanguage = request.getHeader("Accept-Language");
                if (acceptLanguage != null && !acceptLanguage.trim().isEmpty()) {
                    String upper = acceptLanguage.trim().split("[,;-]")[0].toUpperCase();
                    if (upper.equals("EN") || upper.equals("RU") || upper.equals("AZ")) {
                        return upper;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (userId != null) {
            try {
                az.fitnest.catalog.client.CachedUser user = userServiceGrpcClient.getUserById(userId);
                if (user != null && user.getLanguage() != null && !user.getLanguage().isBlank()) {
                    String lang = user.getLanguage().toUpperCase().trim();
                    if (lang.equals("EN") || lang.equals("RU") || lang.equals("AZ")) {
                        return lang;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return az.fitnest.catalog.util.UserContext.getUserLanguage();
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "gym-detail", key = "#userId + '_' + #gymId + '_' + T(az.fitnest.catalog.util.UserContext).getUserLanguage()")
    public GymDetailResponse getGymDetail(Long userId, Long gymId) {
        final String userLang = getUserLanguage(userId);
        Gym gym = gymRepository.findWithDetailsById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));

        CompletableFuture<Boolean> isSavedFuture = CompletableFuture.supplyAsync(() -> {
            if (userId != null) {
                return savedGymRepository.findByUserIdAndGymId(userId, gymId).isPresent();
            }
            return false;
        }, taskExecutor);

        CompletableFuture<List<GymTrainerResponse>> trainerDtosFuture = CompletableFuture
                .supplyAsync(() -> trainerRepository.findByGymId(gymId, PageRequest.of(0, 5, Sort.by("id")))
                        .getContent().stream()
                        .map(GymMapper::toTrainerDto)
                        .toList(), taskExecutor);

        CompletableFuture<List<GymReviewResponse>> recentReviewsFuture = CompletableFuture
                .supplyAsync(() -> {
                    List<az.fitnest.catalog.model.entity.Review> reviews = reviewRepository
                            .findByGymId(gymId, PageRequest.of(0, 3, Sort.by(Sort.Direction.DESC, "createdDate")))
                            .getContent();

                    return reviews.stream()
                            .map(r -> CompletableFuture.supplyAsync(() -> {
                                String fullName = "User " + r.getUserId();
                                String avatarUrl = null;
                                try {
                                    if (r.getUserId() != null) {
                                        CachedUser user = userServiceGrpcClient.getUserById(r.getUserId());
                                        if (user != null) {
                                            fullName = user.getFirstName() + " " + user.getLastName();
                                            avatarUrl = user.getProfileImageUrl();
                                        }
                                    }
                                } catch (Exception e) {
                                }
                                String originalStatus = r.getStatus() != null ? r.getStatus().name() : null;
                                String translatedStatus = (originalStatus != null) ? translationService.getTranslatedValue("REVIEW_STATUS", originalStatus, "name", userLang) : null;
                                if (translatedStatus == null) {
                                    translatedStatus = originalStatus;
                                }
                                return GymMapper.toReviewDto(r, fullName, avatarUrl, translatedStatus);
                            }, taskExecutor))
                            .toList();
                }, taskExecutor)
                .thenCompose(futures -> CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .thenApply(v -> futures.stream().map(CompletableFuture::join).toList()));

        CompletableFuture<List<GymWorkHourResponse>> generalWorkHoursFuture = CompletableFuture.supplyAsync(() -> {
            return GymMapper.toGroupedWorkHourDtos(gymRepository.findGeneralWorkHoursByGymId(gymId), userLang);
        }, taskExecutor);

        CompletableFuture<List<GymRoomResponse>> roomsFuture = CompletableFuture.supplyAsync(() -> {
            if (gym.getRooms() == null) return new ArrayList<>();
            return gym.getRooms().stream().map(room -> {
                String localizedRoomName = translationService.getTranslatedValue("ROOM", room.getId().toString(), "name", userLang);
                if (localizedRoomName == null || localizedRoomName.isEmpty()) localizedRoomName = room.getName();

                List<String> imageUrls = room.getImages() != null
                        ? room.getImages().stream().map(RoomImage::getPictureUrl).toList()
                        : java.util.Collections.emptyList();

                return GymRoomResponse.builder()
                        .id(room.getId())
                        .room_name(localizedRoomName)
                        .urls(imageUrls)
                        .build();
            }).toList();
        }, taskExecutor);

        CompletableFuture.allOf(isSavedFuture, trainerDtosFuture, recentReviewsFuture, generalWorkHoursFuture, roomsFuture).join();

        String userLanguage = userLang;
        boolean isSaved = isSavedFuture.join();
        List<GymReviewResponse> recentReviews = recentReviewsFuture.join();
        List<GymWorkHourResponse> generalWorkHours = generalWorkHoursFuture.join();
        List<GymRoomResponse> rooms = roomsFuture.join();

        CategoryResponse categoryDto = null;
        if (gym.getCategory() != null) {
            Category c = gym.getCategory();
            String localizedCatName = translationService.getTranslatedValue("CATEGORY", c.getId().toString(), "name", userLanguage);
            categoryDto = CategoryResponse.builder()
                    .id(c.getId())
                    .name(localizedCatName != null && !localizedCatName.isEmpty() ? localizedCatName : c.getName())
                    .photoUrl(c.getPhotoUrl())
                    .iconUrl(c.getIconUrl())
                    .coverImageUrl(c.getPhotoUrl())
                    .build();
        }

        List<GymTrainerResponse> trainerDtos = trainerDtosFuture.join().stream().<GymTrainerResponse>map(t -> {
            String localizedTrainerName = translationService.getTranslatedValue("Trainer", t.trainer_id(), "name", userLanguage);
            if (localizedTrainerName == null || localizedTrainerName.isEmpty()) {
                localizedTrainerName = t.name();
            }
            String localizedTrainerSurname = translationService.getTranslatedValue("Trainer", t.trainer_id(), "surname", userLanguage);
            if (localizedTrainerSurname == null || localizedTrainerSurname.isEmpty()) {
                localizedTrainerSurname = t.surname();
            }

            ProfessionResponse profDto = t.profession();
            if (t.profession() != null && t.profession().id() != null) {
                String localizedProfession = translationService.getTranslatedValue("PROFESSION",
                        t.profession().id().toString(), "name", userLanguage);
                if (localizedProfession != null && !localizedProfession.isEmpty()) {
                    profDto = ProfessionResponse.builder()
                            .id(t.profession().id())
                            .name(localizedProfession)
                            .build();
                }
            }

            return GymTrainerResponse.builder()
                    .trainer_id(t.trainer_id())
                    .name(localizedTrainerName)
                    .surname(localizedTrainerSurname)
                    .profession(profDto)
                    .picture(t.picture())
                    .phone(t.phone())
                    .email(t.email())
                    .build();
        }).collect(Collectors.toList());

        List<GymWorkHourResponse> workHoursWoman = null;
        if (gym.getWorkHoursWoman() != null && !gym.getWorkHoursWoman().isEmpty()) {
            workHoursWoman = GymMapper.toGroupedWorkHourDtos(gym.getWorkHoursWoman(), userLanguage);
        }
        List<GymWorkHourResponse> workHoursMan = null;
        if (gym.getWorkHoursMan() != null && !gym.getWorkHoursMan().isEmpty()) {
            workHoursMan = GymMapper.toGroupedWorkHourDtos(gym.getWorkHoursMan(), userLanguage);
        }
        if (generalWorkHours != null && generalWorkHours.isEmpty())
            generalWorkHours = null;

        List<GymPlanItemResponse> supportedSubscriptions = new java.util.ArrayList<>();
        try {
            if (gym.getSubscriptions() != null && !gym.getSubscriptions().isEmpty()) {
                List<Long> packageIds = gym.getSubscriptions().stream()
                        .map(sub -> sub.getPackageId())
                        .filter(java.util.Objects::nonNull)
                        .toList();
                List<az.fitnest.order.grpc.PackageNameInfo> packageInfos = orderServiceGrpcClient
                        .getPackageNamesByIds(packageIds);
                java.util.Map<Long, az.fitnest.order.grpc.PackageNameInfo> idToInfo = packageInfos.stream()
                        .collect(java.util.stream.Collectors.toMap(
                                az.fitnest.order.grpc.PackageNameInfo::getPackageId,
                                p -> p));
                supportedSubscriptions = gym.getSubscriptions().stream()
                        .filter(sub -> sub.getPackageId() != null)
                        .filter(sub -> idToInfo.get(sub.getPackageId()) != null)
                        .map(sub -> {
                            az.fitnest.order.grpc.PackageNameInfo info = idToInfo.get(sub.getPackageId());
                            String planId = sub.getPackageId().toString();
                            String packageName = cleanPackageName(info.getName());
                            List<GymPlanBenefitResponse> benefitsList = sub.getSupportedServices().stream()
                                    .map(b -> {
                                        String localizedBenefit = translationService.getTranslatedValue(
                                                "SUPPORTEDSERVICE", b.getId().toString(), "name", userLanguage);
                                        return GymPlanBenefitResponse.builder()
                                                .description(localizedBenefit != null && !localizedBenefit.isEmpty()
                                                        ? localizedBenefit
                                                        : b.getName())
                                                .iconImageUrl(b.getIconUrl())
                                                .build();
                                    })
                                    .toList();
                            return GymPlanItemResponse.builder()
                                    .plan_id(planId)
                                    .packageName(packageName)
                                    .dailyPrice(sub.getDailyPrice())
                                    .benefits(benefitsList)
                                    .build();
                        })
                        .collect(java.util.stream.Collectors.toList());
            }
        } catch (Exception e) {
            System.err.println("Could not fetch supported subscriptions from order-backend: " + e.getMessage());
        }
        String localizedName = getLocalizedGymName(gym, userLanguage);
        String localizedDescription = translationService.getTranslatedValue("GYM", gym.getId().toString(),
                "description", userLanguage);
        if (localizedDescription == null || localizedDescription.isEmpty())
            localizedDescription = gym.getDescription();
        List<RestDayRequest> restDays = gym.getRestDays() != null
                ? gym.getRestDays().stream()
                        .map(rd -> new RestDayRequest(az.fitnest.catalog.mapper.GymMapper.getLocalizedDay(rd, userLanguage)))
                        .collect(java.util.stream.Collectors.toList())
                : java.util.Collections.emptyList();

        GymDetailResponse response = new GymDetailResponse(
                gym.getId().toString(),
                localizedName,
                localizedDescription,
                gym.getAddress() != null ? new az.fitnest.catalog.dto.response.LocationResponse(
                        gym.getAddress().getLatitude(),
                        gym.getAddress().getLongitude(),
                        getLocalizedAddressField(gym.getId(), "GYM", gym.getAddress(), "addressText",
                                userLanguage),
                        getLocalizedAddressField(gym.getId(), "GYM", gym.getAddress(), "city", userLanguage)
                ) : null,
                isSaved,
                gym.getPhone(),
                gym.getEmail(),
                generalWorkHours,
                workHoursWoman,
                workHoursMan,
                rooms,
                trainerDtos,
                recentReviews,
                categoryDto,
                gym.getCoverImageUrl(),
                gym.getRating() != null ? gym.getRating() : 0.0,
                gym.getReviewsCount() != null ? gym.getReviewsCount() : 0,
                gym.getQrCodeUrl(),
                gym.getStatus(),
                supportedSubscriptions,
                restDays
        );
        return response;
    }

    @Transactional(readOnly = true)
    @Cacheable("gym-images")
    public GymImageResponse getGymImages(Long gymId) {
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));

        List<GymImageItemResponse> items = gymImageRepository.findByGymId(gymId).stream()
                .map(GymMapper::toImageItemDto)
                .collect(Collectors.toList());

        if (gym.getRooms() != null) {
            gym.getRooms().forEach(room -> {
                room.getImages().forEach(img -> {
                    items.add(GymImageItemResponse.builder()
                            .image_id(img.getId() != null ? img.getId().toString() : "room_" + room.getName())
                            .url(img.getPictureUrl())
                            .type("room")
                            .title(room.getName())
                            .build());
                });
            });
        }

        return GymImageResponse.builder().items(items).build();
    }

    @Transactional(readOnly = true)
    public boolean isReservationEnabled(Long gymId) {
        return gymRepository.findById(gymId)
                .map(gym -> Boolean.TRUE.equals(gym.getIsReservationEnabled()))
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getReservationRules(Long gymId) {
        if (!gymRepository.existsById(gymId)) {
            throw new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found");
        }
        return Map.of(
                "cancellation_policy", List.of(
                        "Free cancellation is possible up to 24 hours before the lesson starts",
                        "Cancellation within 24 hours will result in the loss of the session",
                        "In case of no-show, the full amount will be charged"),
                "studio_rules", List.of(
                        "Please arrive at least 10 minutes before the lesson",
                        "Bring your own mat or rent one from the studio",
                        "Wear comfortable sportswear",
                        "Drink water before and after the lesson"),
                "availability", List.of(
                        "Some classes may require confirmation from the studio",
                        "You will receive a confirmation email within 2 hours",
                        "For urgent bookings, please contact the studio directly"));
    }

    @Transactional(readOnly = true)
    public List<GymNearbyResponse> getNearbyGyms(double lat, double lng, double radiusKm) {
        double[] bbox = boundingBox(lat, lng, radiusKm);
        List<Gym> candidates = gymRepository.findByAddressLatitudeBetweenAndAddressLongitudeBetween(bbox[0], bbox[1],
                bbox[2], bbox[3]);
        LocalDateTime newThreshold = LocalDateTime.now().minusDays(30L);
        Long currentUserId = az.fitnest.catalog.util.UserContext.getCurrentUserId();
        String userLanguage = getUserLanguage(currentUserId);

        return candidates.stream()
                .filter(gym -> gym.getAddress() != null && gym.getAddress().getLatitude() != null
                        && gym.getAddress().getLongitude() != null)
                .map(gym -> {
                    String localizedName = getLocalizedGymName(gym, userLanguage);
                    double rawDistance = calculateDistanceRaw(lat, lng, gym.getAddress().getLatitude(),
                            gym.getAddress().getLongitude());
                    boolean isNew = gym.getCreatedDate() != null && gym.getCreatedDate().isAfter(newThreshold);
                    return new Object() {
                        GymNearbyResponse dto = GymNearbyResponse.builder()
                                .gymId(gym.getId())
                                .name(localizedName)
                                .address(gym.getAddress() != null ? gym.getAddress().getAddressText() : null)
                                .rating(gym.getRating())
                                .isNew(isNew)
                                .distanceKm(Math.round(rawDistance * 10.0) / 10.0)
                                .build();
                        double distance = rawDistance;
                    };
                })
                .filter(wrapper -> wrapper.distance <= radiusKm)
                .sorted(Comparator.comparingDouble(wrapper -> wrapper.distance))
                .map(wrapper -> wrapper.dto)
                .toList();
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "main-page-gyms", key = "{#userId, #page, #pageSize, #userLat, #userLng}")
    public PaginatedResponse<GymMainPageResponse> getClosestGyms(Long userId, int page, int pageSize, Double userLat,
            Double userLng) {
        return getGyms(userId, null, "CLOSEST", null, null, page, pageSize, userLat, userLng, "desc");
    }

    @Transactional(readOnly = true)
    @Cacheable(
        value = "gym-listings",
        key = "{#userId != null && 'SAVED'.equalsIgnoreCase(#type) ? #userId : 0, " +
              "#q != null ? #q : '', " +
              "#type != null ? #type : '', " +
              "#categoryId != null ? #categoryId : 0, " +
              "#subscriptionId != null ? #subscriptionId : 0, " +
              "#page, #pageSize, " +
              "#userLat != null ? T(java.lang.Math).round(#userLat * 1000.0) / 1000.0 : null, " +
              "#userLng != null ? T(java.lang.Math).round(#userLng * 1000.0) / 1000.0 : null, " +
              "#sortDir != null ? #sortDir : '', " +
              "T(az.fitnest.catalog.util.UserContext).getUserLanguage()}"
    )
    public PaginatedResponse<GymMainPageResponse> getGyms(Long userId, String q, String type, Long categoryId,
                                                          Long subscriptionId, int page, int pageSize, Double userLat, Double userLng, String sortDir) {
        if (categoryId != null && !categoryRepository.existsById(categoryId)) {
            throw new BadRequestException("INVALID_CATEGORY", "error.invalid_category");
        }

        Page<Gym> gymPage = null;
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = pageable(page, pageSize, Sort.by(direction, "createdDate"));
        String userLanguage = getUserLanguage(userId);

        if ("SAVED".equalsIgnoreCase(type)) {
            if (userId == null)
                return emptyPaginatedResponse(page, pageSize);
            List<SavedGym> saved = savedGymRepository.findByUserIdWithGym(userId);
            List<Gym> candidates = saved.stream().map(SavedGym::getGym).toList();
            return manualPaginate(candidates, userId, userLat, userLng, page, pageSize, q, categoryId, userLanguage);
        }

        String searchKey = (q != null && !q.isBlank()) ? q.trim() : null;

        boolean hasSubscriptionFilter = (subscriptionId != null);
        List<Long> subscriptionIds = hasSubscriptionFilter ? java.util.Collections.singletonList(subscriptionId) : java.util.Collections.singletonList(-1L);

        if (userLat != null && userLng != null && ("CLOSEST".equalsIgnoreCase(type) || type == null || type.isEmpty() || "ALL".equalsIgnoreCase(type))) {
            Pageable distancePageable = PageRequest.of(Math.max(0, page - 1), pageSize);
            Page<Long> idPage = gymRepository.findAllClosestWithFiltersNativeIds(searchKey, categoryId, hasSubscriptionFilter, subscriptionIds, userLat, userLng, distancePageable);
            
            if (idPage.isEmpty()) {
                gymPage = Page.empty(distancePageable);
            } else {
                List<Long> ids = idPage.getContent();
                List<Gym> gyms = gymRepository.findWithListDetailsByIdIn(ids);
                Map<Long, Gym> gymMap = gyms.stream().collect(Collectors.toMap(Gym::getId, g -> g));
                List<Gym> sortedGyms = ids.stream().map(gymMap::get).filter(java.util.Objects::nonNull).toList();
                gymPage = new org.springframework.data.domain.PageImpl<>(sortedGyms, distancePageable, idPage.getTotalElements());
            }
        }

        else {
            Page<Long> idPage = gymRepository.findGymIdsWithFilters(searchKey, categoryId, hasSubscriptionFilter, subscriptionIds, pageable);
            if (idPage.isEmpty()) {
                gymPage = Page.empty(pageable);
            } else {
                List<Long> ids = idPage.getContent();
                List<Gym> gyms = gymRepository.findWithListDetailsByIdIn(ids);
                Map<Long, Gym> gymMap = gyms.stream().collect(Collectors.toMap(Gym::getId, g -> g));
                List<Gym> sortedGyms = ids.stream().map(gymMap::get).filter(java.util.Objects::nonNull).toList();
                gymPage = new org.springframework.data.domain.PageImpl<>(sortedGyms, pageable, idPage.getTotalElements());
            }
        }

        if (gymPage == null) {
            gymPage = Page.empty(pageable);
        }

        Map<Long, List<az.fitnest.catalog.model.entity.GymWorkHour>> globalWorkHoursMap = gymPage.getContent().stream()
                .collect(Collectors.toMap(
                        Gym::getId,
                        gym -> gym.getGeneralWorkHours() != null ? new java.util.ArrayList<>(gym.getGeneralWorkHours()) : java.util.Collections.emptyList(),
                        (existing, replacement) -> existing
                ));

        List<Long> savedGymIds = new java.util.ArrayList<>();
        if (userId != null) {
            List<Long> gymIds = gymPage.getContent().stream().map(Gym::getId).toList();
            if (!gymIds.isEmpty()) {
                savedGymIds = savedGymRepository.findGymIdsByUserIdAndGymIdIn(userId, gymIds);
            }
        }

        final List<Long> finalSavedIds = savedGymIds;

        List<Long> allPackageIds = gymPage.getContent().stream()
                .flatMap(g -> g.getSubscriptions() != null ? g.getSubscriptions().stream() : java.util.stream.Stream.empty())
                .map(az.fitnest.catalog.model.entity.GymSubscription::getPackageId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        List<Long> uncachedPackageIds = allPackageIds.stream()
                .filter(id -> !packageInfoCache.asMap().containsKey(id))
                .toList();
        if (!uncachedPackageIds.isEmpty()) {
            try {
                List<az.fitnest.order.grpc.PackageNameInfo> newInfos = orderServiceGrpcClient.getPackageNamesByIds(uncachedPackageIds);
                for (var info : newInfos) {
                    packageInfoCache.asMap().put(info.getPackageId(), info);
                }
            } catch (Exception e) {
            }
        }

        Map<Long, az.fitnest.order.grpc.PackageNameInfo> finalPackageMap = allPackageIds.stream()
                .filter(packageInfoCache.asMap()::containsKey)
                .collect(Collectors.toMap(id -> id, packageInfoCache.asMap()::get));
        final Map<Long, List<az.fitnest.catalog.model.entity.GymWorkHour>> finalWorkHoursMap = globalWorkHoursMap;

        Map<String, String> translationLookup = fetchTranslationsInBulk(gymPage.getContent(), userLanguage);

        List<GymMainPageResponse> items = gymPage.getContent().stream()
                .map(gym -> mapToGymMainPageDto(
                        gym,
                        userId,
                        userLat,
                        userLng,
                        finalSavedIds.contains(gym.getId()),
                        finalPackageMap,
                        finalWorkHoursMap,
                        translationLookup,
                        userLanguage
                ))
                .collect(Collectors.toList());

        String message = null;
        if (items.isEmpty()) {
            message = messageSource.getMessage("error.gym_not_found", null,
                    org.springframework.context.i18n.LocaleContextHolder.getLocale());
        }

        return PaginatedResponse.<GymMainPageResponse>builder()
                .items(items)
                .total(gymPage.getTotalElements())
                .page(page)
                .pageSize(pageSize)
                .message(message)
                .build();
    }

    @Transactional(readOnly = true)
    public PaginatedResponse<AdminGymResponse> getAllGymsAdmin(String query, String sort, int page, int pageSize) {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        Long userId = UserContext.getCurrentUserId();

        Sort springSort = Sort.unsorted();
        if (sort != null) {
            switch (sort) {
                case "name_asc":
                    springSort = Sort.by(Sort.Direction.ASC, "name");
                    break;
                case "name_desc":
                    springSort = Sort.by(Sort.Direction.DESC, "name");
                    break;
                case "address_asc":
                    springSort = Sort.by(Sort.Direction.ASC, "address.city", "address.addressText");
                    break;
                case "newest":
                    springSort = Sort.by(Sort.Direction.DESC, "createdDate");
                    break;
                case "deactivated":
                    springSort = Sort.by(Sort.Direction.DESC, "status");
                    break;
                default:
                    springSort = Sort.by(Sort.Direction.DESC, "createdDate");
            }
        } else {
            springSort = Sort.by(Sort.Direction.DESC, "createdDate");
        }

        Pageable pageable = PageRequest.of(Math.max(0, page - 1), pageSize, springSort);

        Specification<Gym> spec = (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (query != null && !query.isBlank()) {
                String pattern = "%" + query.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern),
                        cb.like(cb.lower(root.get("address").get("addressText")), pattern),
                        cb.like(cb.lower(root.get("address").get("city")), pattern)));
            }

            if (!isAdmin && userId != null) {
                jakarta.persistence.criteria.Subquery<Long> subquery = criteriaQuery.subquery(Long.class);
                jakarta.persistence.criteria.Root<az.fitnest.catalog.model.entity.GymAdmin> subRoot = subquery.from(az.fitnest.catalog.model.entity.GymAdmin.class);
                subquery.select(subRoot.get("gym").get("id"))
                        .where(cb.equal(subRoot.get("userId"), userId));
                predicates.add(root.get("id").in(subquery));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<Gym> gymPage = gymRepository.findAll(spec, pageable);

        List<Long> gymIds = gymPage.getContent().stream().map(Gym::getId).toList();
        List<az.fitnest.catalog.model.entity.GymAdmin> allAdmins = gymAdminRepository.findAllByGymIdIn(gymIds);
        Map<Long, String> ownerNames = new java.util.HashMap<>();
        Map<Long, List<az.fitnest.catalog.model.entity.GymAdmin>> adminsByGym = allAdmins.stream()
                .filter(admin -> "Super admin".equalsIgnoreCase(admin.getRole()) || "Admin".equalsIgnoreCase(admin.getRole()))
                .filter(admin -> admin.getGym() != null && admin.getGym().getId() != null)
                .collect(Collectors.groupingBy(admin -> admin.getGym().getId()));
        for (Map.Entry<Long, List<az.fitnest.catalog.model.entity.GymAdmin>> entry : adminsByGym.entrySet()) {
            List<az.fitnest.catalog.model.entity.GymAdmin> admins = new java.util.ArrayList<>(entry.getValue());
            admins.sort(java.util.Comparator.comparing(az.fitnest.catalog.model.entity.GymAdmin::getId));
            az.fitnest.catalog.model.entity.GymAdmin firstAdmin = admins.get(0);
            String fullName = (firstAdmin.getName() != null ? firstAdmin.getName() : "") 
                    + " " + (firstAdmin.getSurname() != null ? firstAdmin.getSurname() : "");
            ownerNames.put(entry.getKey(), fullName.trim());
        }

        String userLanguage = resolveUserLanguage();
        List<AdminGymResponse> items = gymPage.getContent().stream().map(gym -> {
        String ownerName = ownerNames.get(gym.getId());

            String city = null;
            String addressText = null;
            if (gym.getAddress() != null) {
                city = getLocalizedAddressField(gym.getId(), "GYM", gym.getAddress(), "city", userLanguage);
                addressText = getLocalizedAddressField(gym.getId(), "GYM", gym.getAddress(), "addressText", userLanguage);
            }
            String fullAddress = (city != null || addressText != null)
                    ? ((city != null ? city : "") + ", " + (addressText != null ? addressText : ""))
                    : "N/A";

            return AdminGymResponse.builder()
                    .id(gym.getId())
                    .name(gym.getName())
                    .fullAddress(fullAddress)
                    .ownerName(ownerName)
                    .status(gym.getStatus())
                    .build();
        }).collect(Collectors.toList());

        return PaginatedResponse.<AdminGymResponse>builder()
                .items(items)
                .total(gymPage.getTotalElements())
                .page(page)
                .pageSize(pageSize)
                .build();
    }

    @Transactional(readOnly = true)
    public List<AdminQrScanHistoryResponse> getUserQrScanHistoryAdmin(Long userId, String query, String sort) {
        return gymEntranceService.getUserQrScanHistoryAdmin(userId, query, sort);
    }

    private PaginatedResponse<GymMainPageResponse> manualPaginate(List<Gym> candidates, Long userId, Double lat,
                                                                  Double lng, int page, int pageSize, String q, Long categoryId, String userLanguage) {
        List<Gym> filtered = candidates.stream()
                .filter(g -> {
                    if (q != null && !q.isBlank()) {
                        String lowerQ = q.toLowerCase();
                        boolean nameMatches = g.getName() != null && g.getName().toLowerCase().contains(lowerQ);
                        boolean addressMatches = g.getAddress() != null && g.getAddress().getAddressText() != null
                                && g.getAddress().getAddressText().toLowerCase().contains(lowerQ);
                        if (!nameMatches && !addressMatches) return false;
                    }
                    if (categoryId != null) {
                        if (g.getCategory() == null || !g.getCategory().getId().equals(categoryId)) return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());

        if (lat != null && lng != null) {
            filtered.sort(Comparator.comparingDouble(g -> {
                if (g.getAddress() != null && g.getAddress().getLatitude() != null && g.getAddress().getLongitude() != null) {
                    return calculateDistanceRaw(lat, lng, g.getAddress().getLatitude(), g.getAddress().getLongitude());
                }
                return Double.MAX_VALUE;
            }));
        }

        int total = filtered.size();
        int from = Math.max(0, (page - 1) * pageSize);
        int to = Math.min(total, from + pageSize);
        List<Gym> pageGymsRaw = from >= total ? new java.util.ArrayList<>() : filtered.subList(from, to);
        List<Long> pageGymIds = pageGymsRaw.stream().map(Gym::getId).toList();
        List<Gym> pageGymsDetailed = pageGymIds.isEmpty() ? List.of() : gymRepository.findWithListDetailsByIdIn(pageGymIds);
        Map<Long, Gym> pageGymsDetailedMap = pageGymsDetailed.stream().collect(Collectors.toMap(Gym::getId, g -> g));
        List<Gym> pageGyms = pageGymIds.stream().map(pageGymsDetailedMap::get).filter(java.util.Objects::nonNull).toList();

        Map<Long, List<az.fitnest.catalog.model.entity.GymWorkHour>> manualWorkHoursMap = pageGyms.stream()
                .collect(Collectors.toMap(
                        Gym::getId,
                        gym -> gym.getGeneralWorkHours() != null ? new java.util.ArrayList<>(gym.getGeneralWorkHours()) : java.util.Collections.emptyList(),
                        (existing, replacement) -> existing
                ));

        List<Long> pagePackageIds = pageGyms.stream()
                .flatMap(g -> g.getSubscriptions() != null ? g.getSubscriptions().stream() : java.util.stream.Stream.empty())
                .map(az.fitnest.catalog.model.entity.GymSubscription::getPackageId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        List<Long> uncachedPackageIds = pagePackageIds.stream()
                .filter(id -> !packageInfoCache.asMap().containsKey(id))
                .toList();
        if (!uncachedPackageIds.isEmpty()) {
            try {
                List<az.fitnest.order.grpc.PackageNameInfo> newInfos = orderServiceGrpcClient.getPackageNamesByIds(uncachedPackageIds);
                for (var info : newInfos) {
                    packageInfoCache.asMap().put(info.getPackageId(), info);
                }
            } catch (Exception e) {
            }
        }

        Map<Long, az.fitnest.order.grpc.PackageNameInfo> manualPackageMap = pagePackageIds.stream()
                .filter(packageInfoCache.asMap()::containsKey)
                .collect(Collectors.toMap(id -> id, packageInfoCache.asMap()::get));

        Map<String, String> translationLookup = fetchTranslationsInBulk(pageGyms, userLanguage);

        List<GymMainPageResponse> pageItems = pageGyms.stream()
                .map(g -> mapToGymMainPageDto(
                        g,
                        userId,
                        lat,
                        lng,
                        true,
                        manualPackageMap,
                        manualWorkHoursMap,
                        translationLookup,
                        userLanguage
                ))
                .collect(Collectors.toList());

        return PaginatedResponse.<GymMainPageResponse>builder()
                .items(pageItems)
                .total(total)
                .page(page)
                .pageSize(pageSize)
                .build();
    }    private GymMainPageResponse mapToGymMainPageDto(
            Gym gym,
            Long userId,
            Double userLat,
            Double userLng,
            boolean isSaved,
            Map<Long, az.fitnest.order.grpc.PackageNameInfo> packageInfoMap,
            Map<Long, List<az.fitnest.catalog.model.entity.GymWorkHour>> workHoursMap,
            Map<String, String> translationLookup,
            String userLanguage) {

        double stars = gym.getRating() != null ? gym.getRating() : 0.0;
        boolean isNew = gym.getCreatedDate() != null
                && gym.getCreatedDate().isAfter(LocalDateTime.now().minusMonths(1L));

        Address address = gym.getAddress();

        Double distanceKm = null;
        if (userLat != null
                && userLng != null
                && address != null
                && address.getLatitude() != null
                && address.getLongitude() != null) {

            distanceKm = Math.round(
                    calculateDistanceRaw(
                            userLat, userLng,
                            address.getLatitude(), address.getLongitude()
                    ) * 10.0
            ) / 10.0;
        }

        CategoryResponse category = null;
        if (gym.getCategory() != null) {
            Category c = gym.getCategory();
            String localizedCatName = getTranslatedValueCached(translationLookup,
                    "CATEGORY", c.getCategoryId().toString(), "name", userLanguage);
            category = CategoryResponse.builder()
                    .id(c.getCategoryId())
                    .name(localizedCatName != null && !localizedCatName.isEmpty()
                            ? localizedCatName : c.getName())
                    .photoUrl(c.getPhotoUrl())
                    .iconUrl(c.getIconUrl())
                    .coverImageUrl(c.getPhotoUrl())
                    .build();
        }

        List<GymPlanItemResponse> supportedSubscriptions = new java.util.ArrayList<>();
        if (gym.getSubscriptions() != null && !gym.getSubscriptions().isEmpty()) {
            supportedSubscriptions = gym.getSubscriptions().stream()
                    .filter(sub -> sub.getPackageId() != null)
                    .map(sub -> {
                        az.fitnest.order.grpc.PackageNameInfo info = packageInfoMap.get(sub.getPackageId());
                        String planId = sub.getPackageId().toString();
                        String packageName = cleanPackageName(info != null ? info.getName() : null);
                        List<GymPlanBenefitResponse> benefitsList = sub.getSupportedServices().stream()
                                .map(b -> {
                                    String localizedBenefit = getTranslatedValueCached(translationLookup,
                                            "SUPPORTEDSERVICE", b.getId().toString(), "name", userLanguage);
                                    return GymPlanBenefitResponse.builder()
                                            .description(localizedBenefit != null && !localizedBenefit.isEmpty()
                                                    ? localizedBenefit : b.getName())
                                            .iconImageUrl(b.getIconUrl())
                                            .build();
                                })
                                .toList();
                        return GymPlanItemResponse.builder()
                                .plan_id(planId)
                                .packageName(packageName)
                                .dailyPrice(sub.getDailyPrice())
                                .benefits(benefitsList)
                                .build();
                    })
                    .toList();
        }

        String localizedName = getLocalizedGymName(gym, userLanguage);

        List<az.fitnest.catalog.model.entity.GymWorkHour> gymWorkHours = workHoursMap.getOrDefault(gym.getId(), java.util.Collections.emptyList());
        String workHoursText = az.fitnest.catalog.mapper.GymMapper.toWorkHoursText(gymWorkHours, userLanguage);

        return GymMainPageResponse.builder()
                .gymId(gym.getId().toString())
                .name(localizedName)
                .coverImageUrl(gym.getCoverImageUrl())
                .stars(stars)
                .isNew(isNew)
                .location(address != null
                        ? getLocalizedAddressField(gym.getId(), "GYM", address, "addressText", translationLookup, userLanguage)
                        : null)
                .city(address != null
                        ? getLocalizedAddressField(gym.getId(), "GYM", address, "city", translationLookup, userLanguage)
                        : null)
                .distanceKm(distanceKm)
                .isSaved(isSaved)
                .category(category)
                .supportedSubscriptions(supportedSubscriptions)
                .workHoursText(workHoursText) // Sizin tələb etdiyiniz yeni sahə
                .build();
    }

    private PaginatedResponse<GymMainPageResponse> emptyPaginatedResponse(int page, int pageSize) {
        return PaginatedResponse.<GymMainPageResponse>builder().items(java.util.Collections.emptyList()).total(0)
                .page(page).pageSize(pageSize).build();
    }

    @Transactional(readOnly = true)
    public LocationResponse getGymLocation(Long gymId) {
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        Address addr = gym.getAddress();
        if (addr == null) {
            return LocationResponse.builder().build();
        }
        return LocationResponse.builder()
                .addressText(addr.getAddressText())
                .latitude(addr.getLatitude())
                .longitude(addr.getLongitude())
                .build();
    }

    @Transactional(readOnly = true)
    public boolean gymSupportsPlan(Long gymId, Long planId) {
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        return orderServiceGrpcClient.checkPackageExists(planId);
    }

    public GymEntranceResponse checkProximity(Double lat, Double lng, Long gymId) {
        return gymEntranceService.checkProximity(lat, lng, gymId);
    }

    public boolean checkGymEntranceEligibilitySimple(Object principal) {
        return gymEntranceService.checkGymEntranceEligibilitySimple(principal);
    }

    @Transactional(readOnly = true)
    public GymCountResponse getGymCount(String type, Long subscriptionId, Long categoryId) {
        java.time.LocalDateTime newThreshold = null;
        if (type != null && type.equalsIgnoreCase("new")) {
            newThreshold = java.time.LocalDateTime.now().minusWeeks(1);
        }
        long count = gymRepository.countGymsWithFilters(categoryId, subscriptionId, newThreshold);
        return new GymCountResponse(count, type != null ? type : "all", subscriptionId, categoryId);
    }

    @Transactional(readOnly = true)
    public GymTypeCountResponse getGymCountByType(String type) {
        long count;
        if (type.equalsIgnoreCase("new")) {
            count = gymRepository.countByCreatedDateAfter(java.time.LocalDateTime.now().minusWeeks(1));
        } else {
            count = gymRepository.count();
        }
        return new GymTypeCountResponse(type, count);
    }

    @Transactional(readOnly = true)
    public List<GymCategoryCountResponse> getGymCountByCategory() {
        Long userId = az.fitnest.catalog.util.UserContext.getCurrentUserId();
        String language = getUserLanguage(userId);
        return self.getGymCountByCategoryCached(language);
    }

    @Cacheable(value = "gym-count-by-category", key = "#language")
    public List<GymCategoryCountResponse> getGymCountByCategoryCached(String language) {
        List<Object[]> results = gymRepository.countGymsByCategory();
        return results.stream()
                .map(row -> {
                    Category cat = (Category) row[0];
                    Long count = (Long) row[1];
                    String catName = "UNKNOWN";
                    String iconUrl = null;
                    if (cat != null) {
                        iconUrl = cat.getIconUrl();
                        String translatedName = translationService.getTranslatedValue("CATEGORY",
                                String.valueOf(cat.getCategoryId()), "name", language);
                        catName = (translatedName != null && !translatedName.isEmpty()) ? translatedName
                                : cat.getName();
                    }
                    return new GymCategoryCountResponse(
                            cat != null ? cat.getCategoryId() : -1L,
                            catName,
                            iconUrl,
                            count);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "gym-count-by-subscription")
    public List<GymSubscriptionCountResponse> getGymCountBySubscription() {
        List<Object[]> results = gymRepository.countGymsBySubscriptionPackageId();
        if (results.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        List<Long> packageIds = results.stream()
                .map(row -> (Long) row[0])
                .filter(java.util.Objects::nonNull)
                .toList();

        List<Long> uncachedPackageIds = packageIds.stream()
                .filter(id -> !packageInfoCache.asMap().containsKey(id))
                .toList();
        if (!uncachedPackageIds.isEmpty()) {
            try {
                List<az.fitnest.order.grpc.PackageNameInfo> newInfos = orderServiceGrpcClient.getPackageNamesByIds(uncachedPackageIds);
                for (var info : newInfos) {
                    packageInfoCache.asMap().put(info.getPackageId(), info);
                }
            } catch (Exception e) {
            }
        }

        return results.stream()
                .map(row -> {
                    Long packageId = (Long) row[0];
                    Long count = (Long) row[1];
                    String packageName = "UNKNOWN";
                    if (packageId != null && packageInfoCache.asMap().containsKey(packageId)) {
                        packageName = packageInfoCache.asMap().get(packageId).getName();
                    }
                    return new GymSubscriptionCountResponse(
                            packageId,
                            packageName,
                            count
                    );
                })
                .toList();
    }

    private Pageable pageable(int page, int size, Sort sort) {
        int safePage = Math.max(page, 1) - 1;
        int safeSize = Math.max(1, Math.min(size, 100));
        return PageRequest.of(safePage, safeSize, sort);
    }

    private double[] boundingBox(double lat, double lng, double radiusKm) {
        double radiusRatio = radiusKm / 6371.0;
        double minLat = lat - Math.toDegrees(radiusRatio);
        double maxLat = lat + Math.toDegrees(radiusRatio);
        double deltaLng = Math.toDegrees(Math.asin(Math.sin(radiusRatio) / Math.cos(Math.toRadians(lat))));
        return new double[] { minLat, maxLat, lng - deltaLng, lng + deltaLng };
    }

    public double calculateDistanceRaw(double lat1, double lng1, double lat2, double lng2) {
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lng2 - lng1);
        double a = Math.sin(latDistance / 2.0) * Math.sin(latDistance / 2.0) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(lonDistance / 2.0) * Math.sin(lonDistance / 2.0);
        return 6371.0 * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }

    @Transactional
    public String getGymQrUrl(Long gymId) {
        return gymEntranceService.getGymQrUrl(gymId);
    }

    @Transactional
    public GymEntranceScanResponse scanGymQrEntrance(Object principal, String qrCodeValue, Double lat, Double lng,
            String userAgent) {
        return gymEntranceService.scanGymQrEntrance(principal, qrCodeValue, lat, lng, userAgent);
    }

    @Transactional(readOnly = true)
    public List<GymEntranceHistoryAdminResponse> getGymEntranceHistory(Long gymId) {
        return gymEntranceService.getGymEntranceHistory(gymId);
    }

    @Transactional(readOnly = true)
    public az.fitnest.catalog.dto.response.GymAnalyticsResponse getGymAnalytics(Long gymId,
            java.time.LocalDateTime startDate, java.time.LocalDateTime endDate, String statusFilter, String sort,
            int page, int pageSize) {
        return gymEntranceService.getGymAnalytics(gymId, startDate, endDate, statusFilter, sort, page, pageSize);
    }

    @Transactional(readOnly = true)
    public GymEntranceEligibilityResponse checkGymEntranceEligibility(Object principal) {
        return gymEntranceService.checkGymEntranceEligibility(principal);
    }

    @Transactional(readOnly = true)
    public GymTypeCountResponse getGymCountByGender(String gender) {
        if (gender == null) {
            throw new BadRequestException("GENDER_REQUIRED", "error.gender_required");
        }
        long count;
        switch (gender.toLowerCase()) {
            case "female":
                count = gymRepository.countGymsWithWorkHoursWoman();
                break;
            case "male":
                count = gymRepository.countGymsWithWorkHoursMan();
                break;
            default:
                throw new BadRequestException("INVALID_GENDER", "error.invalid_gender");
        }
        return new GymTypeCountResponse(gender, count);
    }

    private String getTranslatedValueCached(
            Map<String, String> lookup,
            String entityType,
            String entityId,
            String fieldName,
            String userLanguage) {
        if (userLanguage == null || userLanguage.equalsIgnoreCase("AZ")) {
            return null;
        }
        String key = entityType.toUpperCase() + "_" + entityId + "_" + fieldName.toLowerCase();
        if (lookup != null && lookup.containsKey(key)) {
            return lookup.get(key);
        }
        return translationService.getTranslatedValue(entityType, entityId, fieldName, userLanguage);
    }

    private String getLocalizedAddressField(Long entityId, String entityType,
            az.fitnest.catalog.model.entity.Address address, String fieldName, String userLanguage) {
        return getLocalizedAddressField(entityId, entityType, address, fieldName, null, userLanguage);
    }

    private String getLocalizedAddressField(Long entityId, String entityType,
            az.fitnest.catalog.model.entity.Address address, String fieldName,
            Map<String, String> lookup, String userLanguage) {
        if (address == null)
            return null;
        String localized = getTranslatedValueCached(lookup, entityType, entityId.toString(), fieldName,
                userLanguage);
        if (localized == null || localized.isEmpty()) {
            try {
                java.lang.reflect.Field f = address.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object v = f.get(address);
                if (v != null)
                    return v.toString();
            } catch (Exception ignored) {
            }
            return null;
        }
        return localized;
    }

    private Map<String, String> fetchTranslationsInBulk(List<Gym> gyms, String userLanguage) {
        if (userLanguage == null || userLanguage.equalsIgnoreCase("AZ") || gyms.isEmpty()) {
            return java.util.Collections.emptyMap();
        }

        List<String> gymIds = gyms.stream().map(g -> g.getId().toString()).toList();
        List<String> categoryIds = gyms.stream()
                .map(Gym::getCategory)
                .filter(java.util.Objects::nonNull)
                .map(c -> c.getCategoryId().toString())
                .distinct()
                .toList();
        List<String> subscriptionIds = gyms.stream()
                .flatMap(g -> g.getSubscriptions() != null ? g.getSubscriptions().stream() : java.util.stream.Stream.empty())
                .map(az.fitnest.catalog.model.entity.GymSubscription::getPackageId)
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .distinct()
                .toList();
        List<String> serviceIds = gyms.stream()
                .flatMap(g -> g.getSubscriptions() != null ? g.getSubscriptions().stream() : java.util.stream.Stream.empty())
                .flatMap(sub -> sub.getSupportedServices() != null ? sub.getSupportedServices().stream() : java.util.stream.Stream.empty())
                .map(b -> b.getId().toString())
                .distinct()
                .toList();

        Map<String, String> lookup = new java.util.HashMap<>();

        if (!gymIds.isEmpty()) {
            translationRepository.findByEntityTypeAndEntityIdInAndLanguageCode("GYM", gymIds, userLanguage.toUpperCase())
                    .forEach(t -> lookup.put(t.getEntityType() + "_" + t.getEntityId() + "_" + t.getFieldName().toLowerCase(), t.getFieldValue()));
        }
        if (!categoryIds.isEmpty()) {
            translationRepository.findByEntityTypeAndEntityIdInAndLanguageCode("CATEGORY", categoryIds, userLanguage.toUpperCase())
                    .forEach(t -> lookup.put(t.getEntityType() + "_" + t.getEntityId() + "_" + t.getFieldName().toLowerCase(), t.getFieldValue()));
        }
        if (!subscriptionIds.isEmpty()) {
            translationRepository.findByEntityTypeAndEntityIdInAndLanguageCode("GYMSUBSCRIPTION", subscriptionIds, userLanguage.toUpperCase())
                    .forEach(t -> lookup.put(t.getEntityType() + "_" + t.getEntityId() + "_" + t.getFieldName().toLowerCase(), t.getFieldValue()));
        }
        if (!serviceIds.isEmpty()) {
            translationRepository.findByEntityTypeAndEntityIdInAndLanguageCode("SUPPORTEDSERVICE", serviceIds, userLanguage.toUpperCase())
                    .forEach(t -> lookup.put(t.getEntityType() + "_" + t.getEntityId() + "_" + t.getFieldName().toLowerCase(), t.getFieldValue()));
        }

        return lookup;
    }

    private String getLocalizedGymName(Gym gym, String userLanguage) {
        if (gym == null)
            return null;
        return gym.getName();
    }

    @Override
    @Transactional(readOnly = true)
    public az.fitnest.catalog.dto.response.GymInfoAdminResponse getGymDetailsAdmin(Long gymId) {
        verifyGymOwnership(gymId);
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));

        String userLanguage = resolveUserLanguage();

        Long categoryId = null;
        String categoryName = null;
        if (gym.getCategory() != null) {
            Category cat = gym.getCategory();
            categoryId = cat.getId();
            categoryName = translationService.getTranslatedValue("CATEGORY", categoryId.toString(), "name", userLanguage);
            if (categoryName == null || categoryName.isEmpty()) {
                categoryName = cat.getName();
            }
        }

        List<az.fitnest.catalog.dto.response.RoomImageDto> roomDtos = new java.util.ArrayList<>();
        if (gym.getRooms() != null) {
            for (az.fitnest.catalog.model.entity.Room room : gym.getRooms()) {
                String imgUrl = room.getImages() != null && !room.getImages().isEmpty()
                        ? room.getImages().stream().findFirst().map(RoomImage::getPictureUrl).orElse(null)
                        : null;
                roomDtos.add(az.fitnest.catalog.dto.response.RoomImageDto.builder()
                        .id(room.getId())
                        .name(room.getName())
                        .imageUrl(imgUrl)
                        .build());
            }
        }

        String city = null;
        String addressText = null;
        Double lat = null;
        Double lng = null;
        Double altitude = null;
        if (gym.getAddress() != null) {
            city = getLocalizedAddressField(gym.getId(), "GYM", gym.getAddress(), "city", userLanguage);
            addressText = getLocalizedAddressField(gym.getId(), "GYM", gym.getAddress(), "addressText", userLanguage);
            lat = gym.getAddress().getLatitude();
            lng = gym.getAddress().getLongitude();
            altitude = gym.getAddress().getAltitude();
        }

        String created = "";
        if (gym.getCreatedDate() != null) {
            created = gym.getCreatedDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        }

        List<LessonTypeResponse> lessonTypes = gym.getAvailableLessonTypes().stream()
                .map(lt -> {
                    String localizedLt = translationService.getTranslatedValue("LessonType", lt.getId().toString(), "name", userLanguage);
                    return new LessonTypeResponse(lt.getId(), localizedLt != null ? localizedLt : lt.getName());
                })
                .toList();

        String localizedGymName = gym.getName();

        String localizedGymDescription = translationService.getTranslatedValue("GYM", gym.getId().toString(), "description", userLanguage);
        if (localizedGymDescription == null || localizedGymDescription.isEmpty()) {
            localizedGymDescription = gym.getDescription();
        }

        return az.fitnest.catalog.dto.response.GymInfoAdminResponse.builder()
                .id(gym.getId())
                .categoryId(categoryId)
                .categoryName(categoryName)
                .name(localizedGymName)
                .description(localizedGymDescription)
                .coverImageUrl(gym.getCoverImageUrl())
                .rooms(roomDtos)
                .phone(gym.getPhone())
                .email(gym.getEmail())
                .city(city)
                .address(addressText)
                .latitude(lat)
                .longitude(lng)
                .altitude(altitude)
                .status(gym.getStatus())
                .createdAt(created)
                .lessonTypes(lessonTypes)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public az.fitnest.catalog.dto.response.GymWorkHoursAdminResponse getGymWorkHours(Long gymId) {
        verifyGymOwnership(gymId);
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        
        String userLang = resolveUserLanguage();

        java.util.Set<GymWorkHourResponse> general = gym.getGeneralWorkHours().stream()
                .map(wh -> GymWorkHourResponse.builder()
                        .period(az.fitnest.catalog.mapper.GymMapper.getLocalizedDay(wh.getPeriod(), userLang))
                        .from(wh.getFromTime())
                        .to(wh.getToTime())
                        .build())
                .collect(Collectors.toSet());

        java.util.Set<GymWorkHourResponse> woman = gym.getWorkHoursWoman().stream()
                .map(wh -> GymWorkHourResponse.builder()
                        .period(az.fitnest.catalog.mapper.GymMapper.getLocalizedDay(wh.getPeriod(), userLang))
                        .from(wh.getFromTime())
                        .to(wh.getToTime())
                        .build())
                .collect(Collectors.toSet());

        java.util.Set<GymWorkHourResponse> man = gym.getWorkHoursMan().stream()
                .map(wh -> GymWorkHourResponse.builder()
                        .period(az.fitnest.catalog.mapper.GymMapper.getLocalizedDay(wh.getPeriod(), userLang))
                        .from(wh.getFromTime())
                        .to(wh.getToTime())
                        .build())
                .collect(Collectors.toSet());

        java.util.Set<az.fitnest.catalog.dto.request.RestDayRequest> rests = gym.getRestDays().stream()
                .map(rd -> new az.fitnest.catalog.dto.request.RestDayRequest(az.fitnest.catalog.mapper.GymMapper.getLocalizedDay(rd, userLang)))
                .collect(Collectors.toSet());

        return az.fitnest.catalog.dto.response.GymWorkHoursAdminResponse.builder()
                .generalWorkHours(general)
                .workHoursWoman(woman)
                .workHoursMan(man)
                .restDays(rests)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public az.fitnest.catalog.dto.response.GymSubscriptionsAdminResponse getGymSubscriptions(Long gymId) {
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));

        String userLanguage = resolveUserLanguage();
        List<az.fitnest.catalog.dto.response.GymPlanItemAdminResponse> subscriptions = new java.util.ArrayList<>();

        if (gym.getSubscriptions() != null && !gym.getSubscriptions().isEmpty()) {
            try {
                List<Long> packageIds = gym.getSubscriptions().stream()
                        .map(sub -> sub.getPackageId())
                        .filter(java.util.Objects::nonNull)
                        .toList();

                List<az.fitnest.order.grpc.PackageNameInfo> packageInfos = orderServiceGrpcClient
                        .getPackageNamesByIds(packageIds);
                java.util.Map<Long, az.fitnest.order.grpc.PackageNameInfo> idToInfo = packageInfos.stream()
                        .collect(java.util.stream.Collectors.toMap(
                                az.fitnest.order.grpc.PackageNameInfo::getPackageId,
                                p -> p));

                subscriptions = gym.getSubscriptions().stream()
                        .filter(sub -> sub.getPackageId() != null)
                        .filter(sub -> idToInfo.get(sub.getPackageId()) != null)
                        .map(sub -> {
                            az.fitnest.order.grpc.PackageNameInfo info = idToInfo.get(sub.getPackageId());
                            String localizedName = cleanPackageName(info.getName());

                            List<az.fitnest.catalog.dto.response.GymPlanBenefitAdminResponse> benefits = sub
                                    .getSupportedServices().stream()
                                    .map(b -> {
                                        String localizedBenefit = translationService.getTranslatedValue(
                                                "SUPPORTEDSERVICE", b.getId().toString(), "name", userLanguage);
                                        return az.fitnest.catalog.dto.response.GymPlanBenefitAdminResponse.builder()
                                                .id(b.getId())
                                                .name(localizedBenefit != null && !localizedBenefit.isEmpty()
                                                        ? localizedBenefit
                                                        : b.getName())
                                                .iconImageUrl(b.getIconUrl())
                                                .build();
                                    }).collect(java.util.stream.Collectors.toList());

                            return az.fitnest.catalog.dto.response.GymPlanItemAdminResponse.builder()
                                    .packageId(sub.getPackageId())
                                    .packageName(localizedName)
                                    .dailyPrice(sub.getDailyPrice())
                                    .benefits(benefits)
                                    .build();
                        }).collect(java.util.stream.Collectors.toList());
            } catch (Exception e) {
            }
        }

        return az.fitnest.catalog.dto.response.GymSubscriptionsAdminResponse.builder()
                .gymId(gymId)
                .subscriptions(subscriptions)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<GymAdminResponse> getGymAdmins(Long gymId) {
        if (!gymRepository.existsById(gymId)) {
            throw new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found");
        }

        String userLanguage = resolveUserLanguage();

        return gymAdminRepository.findByGymId(gymId).stream()
                .map(a -> {
                    String localizedName = translationService.getTranslatedValue("GymAdmin", a.getId().toString(), "name", userLanguage);
                    if (localizedName == null || localizedName.isEmpty()) {
                        localizedName = a.getName();
                    }
                    String localizedSurname = translationService.getTranslatedValue("GymAdmin", a.getId().toString(), "surname", userLanguage);
                    if (localizedSurname == null || localizedSurname.isEmpty()) {
                        localizedSurname = a.getSurname();
                    }
                    return GymAdminResponse.builder()
                            .id(a.getId())
                            .userId(a.getUserId())
                            .name(localizedName)
                            .surname(localizedSurname)
                            .phone(a.getPhoneNumber())
                            .email(a.getEmail())
                            .role(a.getRole())
                            .build();
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<ReservationAdminResponse> getGymReservationsAdmin(Long gymId, ReservationStatus status, int page, int pageSize) {
        verifyGymOwnership(gymId);
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), pageSize, Sort.by(Sort.Direction.DESC, "createdDate"));
        Page<Reservation> reservationPage;

        if (status == null) {
            reservationPage = reservationRepository.findByGymId(gymId, pageable);
        } else {
            reservationPage = reservationRepository.findByGymIdAndStatus(gymId, status, pageable);
        }

        List<ReservationAdminResponse> items = reservationPage.getContent().stream()
                .map(r -> {
                    String userFullName = "N/A";
                    try {
                        CachedUser user = userServiceGrpcClient.getUserById(r.getUserId());
                        if (user != null) {
                            userFullName = user.getFirstName() + " " + user.getLastName();
                        }
                    } catch (Exception e) {
                    }

                    String dateStr = r.getReservationDate() != null ? r.getReservationDate().getDate().toString() : "N/A";
                    String timeRange = r.getReservationDate() != null ?
                            r.getReservationDate().getStartTime() + " - " + r.getReservationDate().getEndTime() : "N/A";
                    String trainerName = r.getTrainer() != null ? r.getTrainer().getName() + " " + r.getTrainer().getSurname() : "N/A";

                    return new ReservationAdminResponse(
                        r.getId(),
                        userFullName,
                        dateStr,
                        timeRange,
                        r.getStatus(),
                        trainerName
                    );
                })
                .collect(Collectors.toList());

        return PaginatedResponse.<ReservationAdminResponse>builder()
                .items(items)
                .total(reservationPage.getTotalElements())
                .page(page)
                .pageSize(pageSize)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public ReservationDetailAdminResponse getReservationDetailAdmin(Long reservationId) {
        Reservation r = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("RESERVATION_NOT_FOUND", "error.reservation_not_found"));

        verifyGymOwnership(r.getGym().getId());

        CachedUser user = null;
        try {
            user = userServiceGrpcClient.getUserById(r.getUserId());
        } catch (Exception e) {
        }

        String userFullName = user != null ? user.getFirstName() + " " + user.getLastName() : "N/A";
        String userPhone = user != null ? user.getMobile() : "N/A";
        String userEmail = user != null ? user.getEmail() : "N/A";
        String birthDate = "N/A";
        String regDate = r.getCreatedDate() != null ? r.getCreatedDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")) : "N/A";
        String platform = "N/A";

        String dateStr = r.getReservationDate() != null ? r.getReservationDate().getDate().toString() : "N/A";
        String timeRange = r.getReservationDate() != null ?
                r.getReservationDate().getStartTime() + " - " + r.getReservationDate().getEndTime() : "N/A";
        String trainerName = r.getTrainer() != null ? r.getTrainer().getName() + " " + r.getTrainer().getSurname() : "N/A";

        return new ReservationDetailAdminResponse(
            r.getId(),
            r.getUserId(),
            userFullName,
            userPhone,
            userEmail,
            birthDate,
            regDate,
            platform,
            r.getStatus(),
            trainerName,
            r.getLessonType(),
            dateStr,
            timeRange,
            r.getCancelReasonText()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ReservationStatsResponse getGymReservationStats(Long gymId) {
        verifyGymOwnership(gymId);
        long total = reservationRepository.countByGymId(gymId);
        long pending = reservationRepository.countByGymIdAndStatus(gymId, ReservationStatus.PENDING);
        long confirmed = reservationRepository.countByGymIdAndStatus(gymId, ReservationStatus.APPROVED);
        long cancelled = reservationRepository.countByGymIdAndStatus(gymId, ReservationStatus.CANCELLED);

        return new ReservationStatsResponse(total, pending, confirmed, cancelled);
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<ReservationAdminResponse> getAllReservationsAdmin(ReservationStatus status, int page, int pageSize) {
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), pageSize, Sort.by(Sort.Direction.DESC, "createdDate"));
        Page<Reservation> reservationPage;

        if (status == null) {
            reservationPage = reservationRepository.findAll(pageable);
        } else {
            reservationPage = reservationRepository.findByStatus(status, pageable);
        }

        List<ReservationAdminResponse> items = reservationPage.getContent().stream()
                .map(r -> {
                    String userFullName = "N/A";
                    try {
                        CachedUser user = userServiceGrpcClient.getUserById(r.getUserId());
                        if (user != null) {
                            userFullName = user.getFirstName() + " " + user.getLastName();
                        }
                    } catch (Exception e) {
                    }

                    String dateStr = r.getReservationDate() != null ? r.getReservationDate().getDate().toString() : "N/A";
                    String timeRange = r.getReservationDate() != null ?
                            r.getReservationDate().getStartTime() + " - " + r.getReservationDate().getEndTime() : "N/A";
                    String trainerName = r.getTrainer() != null ? r.getTrainer().getName() + " " + r.getTrainer().getSurname() : "N/A";

                    return new ReservationAdminResponse(
                        r.getId(),
                        userFullName,
                        dateStr,
                        timeRange,
                        r.getStatus(),
                        trainerName
                    );
                })
                .collect(Collectors.toList());

        return PaginatedResponse.<ReservationAdminResponse>builder()
                .items(items)
                .total(reservationPage.getTotalElements())
                .page(page)
                .pageSize(pageSize)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<az.fitnest.catalog.dto.response.LessonHourResponse> getGymLessonHoursAdmin(Long gymId, java.time.LocalDate startDate, java.time.LocalDate endDate, int page, int pageSize) {
        verifyGymOwnership(gymId);

        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Baku"));
        // Bu endpoint heç vaxt bugündən köhnə dərsləri göstərmir; keçmiş "Arxiv" endpoint-inin işidir.
        java.time.LocalDate effectiveStart = (startDate == null || startDate.isBefore(today)) ? today : startDate;

        List<az.fitnest.catalog.model.entity.TrainerReservationDate> source = (endDate != null)
                ? trainerReservationDateRepository.findByGymIdAndDateBetween(gymId, effectiveStart, endDate)
                : trainerReservationDateRepository.findByGymIdAndDateGreaterThanEqual(gymId, effectiveStart);

        List<az.fitnest.catalog.model.entity.TrainerReservationDate> filteredSource = source.stream()
                .filter(trd -> trd.getStatus() != az.fitnest.catalog.model.enums.SessionStatus.CANCELLED)
                .collect(Collectors.toList());

        List<Long> sessionIds = filteredSource.stream().map(az.fitnest.catalog.model.entity.TrainerReservationDate::getId).collect(Collectors.toList());
        java.util.Map<Long, Long> pendingMap = new java.util.HashMap<>();
        java.util.Map<Long, Long> approvedMap = new java.util.HashMap<>();

        if (!sessionIds.isEmpty()) {
            List<Object[]> pendingCounts = reservationRepository.countActiveReservationsForSessions(
                    sessionIds, List.of(az.fitnest.catalog.model.enums.ReservationStatus.PENDING));
            for (Object[] row : pendingCounts) {
                pendingMap.put((Long) row[0], ((Number) row[1]).longValue());
            }

            List<Object[]> approvedCounts = reservationRepository.countActiveReservationsForSessions(
                    sessionIds, List.of(az.fitnest.catalog.model.enums.ReservationStatus.APPROVED));
            for (Object[] row : approvedCounts) {
                approvedMap.put((Long) row[0], ((Number) row[1]).longValue());
            }
        }

        List<az.fitnest.catalog.dto.response.LessonHourResponse> allHours = filteredSource.stream()
                .map(trd -> toLessonHourResponse(trd, pendingMap, approvedMap))
                .sorted(Comparator.comparing(az.fitnest.catalog.dto.response.LessonHourResponse::date)
                        .thenComparing(az.fitnest.catalog.dto.response.LessonHourResponse::timeRange))
                .collect(Collectors.toList());

        return paginateLessonHours(allHours, page, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<az.fitnest.catalog.dto.response.LessonHourResponse> getGymLessonHoursArchiveAdmin(Long gymId, int page, int pageSize) {
        verifyGymOwnership(gymId);

        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Baku"));

        List<az.fitnest.catalog.model.entity.TrainerReservationDate> source = trainerReservationDateRepository
                .findByGymIdAndDateBefore(gymId, today).stream()
                .filter(trd -> trd.getStatus() != az.fitnest.catalog.model.enums.SessionStatus.CANCELLED)
                .collect(Collectors.toList());

        List<Long> sessionIds = source.stream().map(az.fitnest.catalog.model.entity.TrainerReservationDate::getId).collect(Collectors.toList());
        java.util.Map<Long, Long> pendingMap = new java.util.HashMap<>();
        java.util.Map<Long, Long> approvedMap = new java.util.HashMap<>();

        if (!sessionIds.isEmpty()) {
            List<Object[]> pendingCounts = reservationRepository.countActiveReservationsForSessions(
                    sessionIds, List.of(az.fitnest.catalog.model.enums.ReservationStatus.PENDING));
            for (Object[] row : pendingCounts) {
                pendingMap.put((Long) row[0], ((Number) row[1]).longValue());
            }

            List<Object[]> approvedCounts = reservationRepository.countActiveReservationsForSessions(
                    sessionIds, List.of(az.fitnest.catalog.model.enums.ReservationStatus.APPROVED));
            for (Object[] row : approvedCounts) {
                approvedMap.put((Long) row[0], ((Number) row[1]).longValue());
            }
        }

        List<az.fitnest.catalog.dto.response.LessonHourResponse> allHours = source.stream()
                .map(trd -> toLessonHourResponse(trd, pendingMap, approvedMap))
                .sorted(Comparator.comparing(az.fitnest.catalog.dto.response.LessonHourResponse::date).reversed()
                        .thenComparing(az.fitnest.catalog.dto.response.LessonHourResponse::timeRange, Comparator.reverseOrder()))
                .collect(Collectors.toList());

        return paginateLessonHours(allHours, page, pageSize);
    }

    @Override
    @Transactional(readOnly = true)
    public az.fitnest.catalog.dto.response.LessonHourReservationCountsResponse getLessonHourReservationCounts(Long lessonHourId) {
        trainerReservationDateRepository.findById(lessonHourId).ifPresent(session -> {
            Long gymId = session.getGymId();
            if (gymId != null) {
                verifyGymOwnership(gymId);
            }
        });

        long pending = reservationRepository.countByReservationDateIdAndStatus(lessonHourId, az.fitnest.catalog.model.enums.ReservationStatus.PENDING);
        long approved = reservationRepository.countByReservationDateIdAndStatus(lessonHourId, az.fitnest.catalog.model.enums.ReservationStatus.APPROVED);

        return new az.fitnest.catalog.dto.response.LessonHourReservationCountsResponse(pending, approved);
    }

    private az.fitnest.catalog.dto.response.LessonHourResponse toLessonHourResponse(
            az.fitnest.catalog.model.entity.TrainerReservationDate trd,
            java.util.Map<Long, Long> pendingMap,
            java.util.Map<Long, Long> approvedMap) {
        long pending = pendingMap.getOrDefault(trd.getId(), 0L);
        long approved = approvedMap.getOrDefault(trd.getId(), 0L);
        return new az.fitnest.catalog.dto.response.LessonHourResponse(
                trd.getId(),
                trd.getTrainer() != null ? trd.getTrainer().getName() + " " + trd.getTrainer().getSurname() : "N/A",
                trd.getClassType() != null ? trd.getClassType().getName() : "N/A",
                trd.getDate(),
                trd.getStartTime() + " - " + trd.getEndTime(),
                trd.getEmptySpaces(),
                trd.getStatus(),
                (int) pending,
                (int) approved
        );
    }

    private PaginatedResponse<az.fitnest.catalog.dto.response.LessonHourResponse> paginateLessonHours(
            List<az.fitnest.catalog.dto.response.LessonHourResponse> allHours, int page, int pageSize) {
        int from = Math.max(0, (page - 1) * pageSize);
        int to = Math.min(allHours.size(), from + pageSize);
        List<az.fitnest.catalog.dto.response.LessonHourResponse> pageItems = from >= allHours.size()
                ? new java.util.ArrayList<>()
                : new java.util.ArrayList<>(allHours.subList(from, to));

        return PaginatedResponse.<az.fitnest.catalog.dto.response.LessonHourResponse>builder()
                .items(pageItems)
                .total((long) allHours.size())
                .page(page)
                .pageSize(pageSize)
                .build();
    }

    private String cleanPackageName(String name) {
        if (name == null) return null;
        return name.replace(" Plan", "").replace(" plan", "").trim();
    }

    private void verifyGymOwnership(Long gymId) {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            throw new UnauthorizedException("Unauthorized");
        }
        boolean isAdmin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (isAdmin) {
            return;
        }
        Long userId = UserContext.getCurrentUserId();
        if (userId == null || !gymAdminRepository.existsByGymIdAndUserId(gymId, userId)) {
            throw new ForbiddenException("You do not have access to this gym");
        }
    }

    private int getPackageRank(String packageName) {
        if (packageName == null) {
            return 0;
        }
        String lower = packageName.toLowerCase();
        if (lower.contains("platinum")) {
            return 4;
        }
        if (lower.contains("gold")) {
            return 3;
        }
        if (lower.contains("silver")) {
            return 2;
        }
        if (lower.contains("bronze")) {
            return 1;
        }
        return 0;
    }

    private List<Long> getEligibleSubscriptionIds(Long subscriptionId) {
        if (subscriptionId == null) {
            return java.util.Collections.emptyList();
        }
        return eligibleSubscriptionIdsCache.get(subscriptionId, id -> {
            try {
                List<az.fitnest.order.grpc.SubscriptionPackageInfo> allPlans = orderServiceGrpcClient.getGymPlans();
                String userPackageName = null;
                for (var plan : allPlans) {
                    if (plan.getPackageId() == id.longValue()) {
                        userPackageName = plan.getName();
                        break;
                    }
                }
                if (userPackageName == null) {
                    List<az.fitnest.order.grpc.PackageNameInfo> nameInfos = orderServiceGrpcClient.getPackageNamesByIds(java.util.List.of(id));
                    if (!nameInfos.isEmpty()) {
                        userPackageName = nameInfos.get(0).getName();
                    }
                }
                
                int userRank = getPackageRank(userPackageName);
                List<Long> eligibleIds = new java.util.ArrayList<>();
                for (var plan : allPlans) {
                    int planRank = getPackageRank(plan.getName());
                    if (planRank <= userRank) {
                        eligibleIds.add(plan.getPackageId());
                    }
                }
                if (!eligibleIds.contains(id)) {
                    eligibleIds.add(id);
                }
                return eligibleIds;
            } catch (Exception e) {
                return java.util.Collections.singletonList(id);
            }
        });
    }

    @Override
    @Transactional(readOnly = true)
    public GymDetailResponseV3 getGymDetailV3(Long userId, Long gymId, Double lat, Double lng) {
        return gymDetailV3Service.load(userId, gymId, lat, lng, getUserLanguage(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public GymDetailResponseV2 getGymDetailV2(Long userId, Long gymId) {
        final String userLang = getUserLanguage(userId);
        Gym gym = gymRepository.findWithDetailsById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));

        CompletableFuture<Boolean> isSavedFuture = CompletableFuture.supplyAsync(() -> {
            if (userId != null) {
                return savedGymRepository.findByUserIdAndGymId(userId, gymId).isPresent();
            }
            return false;
        }, taskExecutor);

        CompletableFuture<List<GymTrainerResponse>> trainerDtosFuture = CompletableFuture
                .supplyAsync(() -> trainerRepository.findByGymId(gymId, PageRequest.of(0, 5, Sort.by("id")))
                        .getContent().stream()
                        .map(GymMapper::toTrainerDto)
                        .toList(), taskExecutor);

        CompletableFuture<List<GymReviewResponse>> recentReviewsFuture = CompletableFuture
                .supplyAsync(() -> {
                    List<az.fitnest.catalog.model.entity.Review> reviews = reviewRepository
                            .findByGymId(gymId, PageRequest.of(0, 3, Sort.by(Sort.Direction.DESC, "createdDate")))
                            .getContent();

                    return reviews.stream()
                            .map(r -> CompletableFuture.supplyAsync(() -> {
                                String fullName = "User " + r.getUserId();
                                String avatarUrl = null;
                                try {
                                    if (r.getUserId() != null) {
                                        CachedUser user = userServiceGrpcClient.getUserById(r.getUserId());
                                        if (user != null) {
                                            fullName = user.getFirstName() + " " + user.getLastName();
                                            avatarUrl = user.getProfileImageUrl();
                                        }
                                    }
                                } catch (Exception e) {
                                }
                                String originalStatus = r.getStatus() != null ? r.getStatus().name() : null;
                                String translatedStatus = (originalStatus != null) ? translationService.getTranslatedValue("REVIEW_STATUS", originalStatus, "name", userLang) : null;
                                if (translatedStatus == null) {
                                    translatedStatus = originalStatus;
                                }
                                return GymMapper.toReviewDto(r, fullName, avatarUrl, translatedStatus);
                            }, taskExecutor))
                            .toList();
                }, taskExecutor)
                .thenCompose(futures -> CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .thenApply(v -> futures.stream().map(CompletableFuture::join).toList()));

        CompletableFuture<List<GymWorkHourResponse>> generalWorkHoursFuture = CompletableFuture.supplyAsync(() -> {
            return GymMapper.toGroupedWorkHourDtos(gymRepository.findGeneralWorkHoursByGymId(gymId), userLang);
        }, taskExecutor);

        CompletableFuture<List<GymRoomResponseV2>> roomsFuture = CompletableFuture.supplyAsync(() -> {
            if (gym.getRooms() == null) return new ArrayList<>();
            return gym.getRooms().stream().map(room -> {
                String localizedRoomName = translationService.getTranslatedValue("ROOM", room.getId().toString(), "name", userLang);
                if (localizedRoomName == null || localizedRoomName.isEmpty()) localizedRoomName = room.getName();

                List<String> imageUrls = room.getImages() != null
                        ? room.getImages().stream().map(RoomImage::getPictureUrl).toList()
                        : java.util.Collections.emptyList();

                return GymRoomResponseV2.builder()
                        .id(room.getId())
                        .room_name(localizedRoomName)
                        .categoryId(room.getCategory() != null ? room.getCategory().getId() : null)
                        .urls(imageUrls)
                        .build();
            }).toList();
        }, taskExecutor);

        CompletableFuture.allOf(isSavedFuture, trainerDtosFuture, recentReviewsFuture, generalWorkHoursFuture, roomsFuture).join();

        String userLanguage = userLang;
        boolean isSaved = isSavedFuture.join();
        List<GymReviewResponse> recentReviews = recentReviewsFuture.join();
        List<GymWorkHourResponse> generalWorkHours = generalWorkHoursFuture.join();
        List<GymRoomResponseV2> rooms = roomsFuture.join();

        List<CategoryResponse> categoryDtos = new java.util.ArrayList<>();
        if (gym.getCategory() != null) {
            Category c = gym.getCategory();
            String localizedCatName = translationService.getTranslatedValue("CATEGORY", c.getId().toString(), "name", userLanguage);
            categoryDtos.add(CategoryResponse.builder()
                    .id(c.getId())
                    .name(localizedCatName != null && !localizedCatName.isEmpty() ? localizedCatName : c.getName())
                    .photoUrl(c.getPhotoUrl())
                    .iconUrl(c.getIconUrl())
                    .coverImageUrl(c.getPhotoUrl())
                    .build());
        }
        if (gym.getSubCategory() != null) {
            Category c = gym.getSubCategory();
            String localizedCatName = translationService.getTranslatedValue("CATEGORY", c.getId().toString(), "name", userLanguage);
            categoryDtos.add(CategoryResponse.builder()
                    .id(c.getId())
                    .name(localizedCatName != null && !localizedCatName.isEmpty() ? localizedCatName : c.getName())
                    .photoUrl(c.getPhotoUrl())
                    .iconUrl(c.getIconUrl())
                    .coverImageUrl(c.getPhotoUrl())
                    .build());
        }

        List<GymTrainerResponse> trainerDtos = trainerDtosFuture.join().stream().<GymTrainerResponse>map(t -> {
            String localizedTrainerName = translationService.getTranslatedValue("Trainer", t.trainer_id(), "name", userLanguage);
            if (localizedTrainerName == null || localizedTrainerName.isEmpty()) {
                localizedTrainerName = t.name();
            }
            String localizedTrainerSurname = translationService.getTranslatedValue("Trainer", t.trainer_id(), "surname", userLanguage);
            if (localizedTrainerSurname == null || localizedTrainerSurname.isEmpty()) {
                localizedTrainerSurname = t.surname();
            }

            ProfessionResponse profDto = t.profession();
            if (t.profession() != null && t.profession().id() != null) {
                String localizedProfession = translationService.getTranslatedValue("PROFESSION",
                        t.profession().id().toString(), "name", userLanguage);
                if (localizedProfession != null && !localizedProfession.isEmpty()) {
                    profDto = ProfessionResponse.builder()
                            .id(t.profession().id())
                            .name(localizedProfession)
                            .build();
                }
            }

            return GymTrainerResponse.builder()
                    .trainer_id(t.trainer_id())
                    .name(localizedTrainerName)
                    .surname(localizedTrainerSurname)
                    .profession(profDto)
                    .picture(t.picture())
                    .phone(t.phone())
                    .email(t.email())
                    .build();
        }).collect(Collectors.toList());

        List<GymWorkHourResponse> workHoursWoman = null;
        if (gym.getWorkHoursWoman() != null && !gym.getWorkHoursWoman().isEmpty()) {
            workHoursWoman = GymMapper.toGroupedWorkHourDtos(gym.getWorkHoursWoman(), userLanguage);
        }
        List<GymWorkHourResponse> workHoursMan = null;
        if (gym.getWorkHoursMan() != null && !gym.getWorkHoursMan().isEmpty()) {
            workHoursMan = GymMapper.toGroupedWorkHourDtos(gym.getWorkHoursMan(), userLanguage);
        }
        if (generalWorkHours != null && generalWorkHours.isEmpty())
            generalWorkHours = null;

        List<GymPlanItemResponseV2> supportedSubscriptions = new java.util.ArrayList<>();
        try {
            if (gym.getSubscriptions() != null && !gym.getSubscriptions().isEmpty()) {
                List<Long> packageIds = gym.getSubscriptions().stream()
                        .map(sub -> sub.getPackageId())
                        .filter(java.util.Objects::nonNull)
                        .toList();
                List<az.fitnest.order.grpc.PackageNameInfo> packageInfos = orderServiceGrpcClient
                        .getPackageNamesByIds(packageIds);
                java.util.Map<Long, az.fitnest.order.grpc.PackageNameInfo> idToInfo = packageInfos.stream()
                        .collect(java.util.stream.Collectors.toMap(
                                az.fitnest.order.grpc.PackageNameInfo::getPackageId,
                                p -> p));
                supportedSubscriptions = gym.getSubscriptions().stream()
                        .filter(sub -> sub.getPackageId() != null)
                        .filter(sub -> idToInfo.get(sub.getPackageId()) != null)
                        .map(sub -> {
                            az.fitnest.order.grpc.PackageNameInfo info = idToInfo.get(sub.getPackageId());
                            String planId = sub.getPackageId().toString();
                            String packageName = cleanPackageName(info.getName());
                            List<GymPlanBenefitResponse> benefitsList = sub.getSupportedServices().stream()
                                    .map(b -> {
                                        String localizedBenefit = translationService.getTranslatedValue(
                                                "SUPPORTEDSERVICE", b.getId().toString(), "name", userLanguage);
                                        return GymPlanBenefitResponse.builder()
                                                .description(localizedBenefit != null && !localizedBenefit.isEmpty()
                                                        ? localizedBenefit
                                                        : b.getName())
                                                .iconImageUrl(b.getIconUrl())
                                                .build();
                                    })
                                    .toList();
                            return GymPlanItemResponseV2.builder()
                                    .plan_id(planId)
                                    .packageName(packageName)
                                    .categoryId(sub.getCategory() != null ? sub.getCategory().getId() : null)
                                    .dailyPrice(sub.getDailyPrice())
                                    .benefits(benefitsList)
                                    .build();
                        })
                        .collect(java.util.stream.Collectors.toList());
            }
        } catch (Exception e) {
            System.err.println("Could not fetch supported subscriptions from order-backend: " + e.getMessage());
        }
        String localizedName = getLocalizedGymName(gym, userLanguage);
        String localizedDescription = translationService.getTranslatedValue("GYM", gym.getId().toString(),
                "description", userLanguage);
        if (localizedDescription == null || localizedDescription.isEmpty())
            localizedDescription = gym.getDescription();
        List<RestDayRequest> restDays = gym.getRestDays() != null
                ? gym.getRestDays().stream()
                        .map(rd -> new RestDayRequest(az.fitnest.catalog.mapper.GymMapper.getLocalizedDay(rd, userLanguage)))
                        .collect(java.util.stream.Collectors.toList())
                : java.util.Collections.emptyList();

        return new GymDetailResponseV2(
                gym.getId().toString(),
                localizedName,
                localizedDescription,
                gym.getAddress() != null ? new az.fitnest.catalog.dto.response.LocationResponse(
                        gym.getAddress().getLatitude(),
                        gym.getAddress().getLongitude(),
                        getLocalizedAddressField(gym.getId(), "GYM", gym.getAddress(), "addressText",
                                userLanguage),
                        getLocalizedAddressField(gym.getId(), "GYM", gym.getAddress(), "city", userLanguage)
                ) : null,
                isSaved,
                gym.getPhone(),
                gym.getEmail(),
                generalWorkHours,
                workHoursWoman,
                workHoursMan,
                rooms,
                trainerDtos,
                recentReviews,
                categoryDtos,
                gym.getCoverImageUrl(),
                gym.getRating() != null ? gym.getRating() : 0.0,
                gym.getReviewsCount() != null ? gym.getReviewsCount() : 0,
                gym.getQrCodeUrl(),
                gym.getStatus(),
                supportedSubscriptions,
                restDays
        );
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<GymMainPageResponseV2> getGymsV2(Long userId, String q, String type, Long categoryId,
                                                              Long subscriptionId, int page, int pageSize, Double userLat, Double userLng, String sortDir) {
        if (categoryId != null && !categoryRepository.existsById(categoryId)) {
            throw new BadRequestException("INVALID_CATEGORY", "error.invalid_category");
        }

        Page<Gym> gymPage = null;
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = pageable(page, pageSize, Sort.by(direction, "createdDate"));
        String userLanguage = getUserLanguage(userId);

        if ("SAVED".equalsIgnoreCase(type)) {
            if (userId == null)
                return emptyPaginatedResponseV2(page, pageSize);
            List<SavedGym> saved = savedGymRepository.findByUserIdWithGym(userId);
            List<Gym> candidates = saved.stream().map(SavedGym::getGym).toList();
            List<Gym> filtered = candidates.stream()
                    .filter(g -> {
                        if (q != null && !q.isBlank()) {
                            String lowerQ = q.toLowerCase();
                            boolean nameMatches = g.getName() != null && g.getName().toLowerCase().contains(lowerQ);
                            boolean addressMatches = g.getAddress() != null && g.getAddress().getAddressText() != null
                                    && g.getAddress().getAddressText().toLowerCase().contains(lowerQ);
                            if (!nameMatches && !addressMatches) return false;
                        }
                        if (categoryId != null) {
                            if (g.getCategories() == null || g.getCategories().stream().noneMatch(c -> c.getId().equals(categoryId))) return false;
                        }
                        return true;
                    })
                    .collect(Collectors.toList());

            if (userLat != null && userLng != null) {
                filtered.sort(Comparator.comparingDouble(g -> calculateDistanceRaw(userLat, userLng, g.getAddress().getLatitude(), g.getAddress().getLongitude())));
            }
            int start = (page - 1) * pageSize;
            int end = Math.min(start + pageSize, filtered.size());
            List<Gym> paginated = (start >= filtered.size()) ? new java.util.ArrayList<>() : filtered.subList(start, end);
            org.springframework.data.domain.PageImpl<Gym> manualPage = new org.springframework.data.domain.PageImpl<>(paginated, pageable, filtered.size());
            gymPage = manualPage;
        } else {
            String searchKey = (q != null && !q.isBlank()) ? q.trim() : null;

            boolean hasSubscriptionFilter = (subscriptionId != null);
            List<Long> subscriptionIds = hasSubscriptionFilter ? java.util.Collections.singletonList(subscriptionId) : java.util.Collections.singletonList(-1L);

            if (userLat != null && userLng != null && ("CLOSEST".equalsIgnoreCase(type) || type == null || type.isEmpty() || "ALL".equalsIgnoreCase(type))) {
                Pageable distancePageable = PageRequest.of(Math.max(0, page - 1), pageSize);
                Page<Long> idPage = gymRepository.findAllClosestWithFiltersNativeIdsV2(searchKey, categoryId, hasSubscriptionFilter, subscriptionIds, userLat, userLng, distancePageable);
                
                if (idPage.isEmpty()) {
                    gymPage = Page.empty(distancePageable);
                } else {
                    List<Long> ids = idPage.getContent();
                    List<Gym> gyms = gymRepository.findWithListDetailsByIdIn(ids);
                    Map<Long, Gym> gymMap = gyms.stream().collect(Collectors.toMap(Gym::getId, g -> g));
                    List<Gym> sortedGyms = ids.stream().map(gymMap::get).filter(java.util.Objects::nonNull).toList();
                    gymPage = new org.springframework.data.domain.PageImpl<>(sortedGyms, distancePageable, idPage.getTotalElements());
                }
            } else {
                Page<Long> idPage = gymRepository.findGymIdsWithFiltersV2(searchKey, categoryId, hasSubscriptionFilter, subscriptionIds, pageable);
                if (idPage.isEmpty()) {
                    gymPage = Page.empty(pageable);
                } else {
                    List<Long> ids = idPage.getContent();
                    List<Gym> gyms = gymRepository.findWithListDetailsByIdIn(ids);
                    Map<Long, Gym> gymMap = gyms.stream().collect(Collectors.toMap(Gym::getId, g -> g));
                    List<Gym> sortedGyms = ids.stream().map(gymMap::get).filter(java.util.Objects::nonNull).toList();
                    gymPage = new org.springframework.data.domain.PageImpl<>(sortedGyms, pageable, idPage.getTotalElements());
                }
            }
        }

        if (gymPage == null) {
            gymPage = Page.empty(pageable);
        }

        Map<Long, List<az.fitnest.catalog.model.entity.GymWorkHour>> globalWorkHoursMap = gymPage.getContent().stream()
                .collect(Collectors.toMap(
                        Gym::getId,
                        gym -> gym.getGeneralWorkHours() != null ? new java.util.ArrayList<>(gym.getGeneralWorkHours()) : java.util.Collections.emptyList(),
                        (existing, replacement) -> existing
                ));

        List<Long> savedGymIds = new java.util.ArrayList<>();
        if (userId != null) {
            List<Long> gymIds = gymPage.getContent().stream().map(Gym::getId).toList();
            if (!gymIds.isEmpty()) {
                savedGymIds = savedGymRepository.findGymIdsByUserIdAndGymIdIn(userId, gymIds);
            }
        }

        final List<Long> finalSavedIds = savedGymIds;

        List<Long> allPackageIds = gymPage.getContent().stream()
                .flatMap(g -> g.getSubscriptions() != null ? g.getSubscriptions().stream() : java.util.stream.Stream.empty())
                .map(az.fitnest.catalog.model.entity.GymSubscription::getPackageId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        List<Long> uncachedPackageIds = allPackageIds.stream()
                .filter(id -> !packageInfoCache.asMap().containsKey(id))
                .toList();
        if (!uncachedPackageIds.isEmpty()) {
            try {
                List<az.fitnest.order.grpc.PackageNameInfo> newInfos = orderServiceGrpcClient.getPackageNamesByIds(uncachedPackageIds);
                for (var info : newInfos) {
                    packageInfoCache.asMap().put(info.getPackageId(), info);
                }
            } catch (Exception e) {
            }
        }

        Map<Long, az.fitnest.order.grpc.PackageNameInfo> finalPackageMap = allPackageIds.stream()
                .filter(packageInfoCache.asMap()::containsKey)
                .collect(Collectors.toMap(id -> id, packageInfoCache.asMap()::get));
        final Map<Long, List<az.fitnest.catalog.model.entity.GymWorkHour>> finalWorkHoursMap = globalWorkHoursMap;

        Map<String, String> translationLookup = fetchTranslationsInBulk(gymPage.getContent(), userLanguage);

        List<GymMainPageResponseV2> items = gymPage.getContent().stream()
                .map(gym -> mapToGymMainPageDtoV2(
                        gym,
                        userId,
                        userLat,
                        userLng,
                        finalSavedIds.contains(gym.getId()),
                        finalPackageMap,
                        finalWorkHoursMap,
                        translationLookup,
                        userLanguage
                ))
                .collect(Collectors.toList());

        String message = null;
        if (items.isEmpty()) {
            message = messageSource.getMessage("error.gym_not_found", null,
                    org.springframework.context.i18n.LocaleContextHolder.getLocale());
        }

        return PaginatedResponse.<GymMainPageResponseV2>builder()
                .items(items)
                .total(gymPage.getTotalElements())
                .page(page)
                .pageSize(pageSize)
                .message(message)
                .build();
    }

    private PaginatedResponse<GymMainPageResponseV2> emptyPaginatedResponseV2(int page, int pageSize) {
        return PaginatedResponse.<GymMainPageResponseV2>builder()
                .items(java.util.Collections.emptyList())
                .total(0L)
                .page(page)
                .pageSize(pageSize)
                .build();
    }

    private GymMainPageResponseV2 mapToGymMainPageDtoV2(
            Gym gym,
            Long userId,
            Double userLat,
            Double userLng,
            boolean isSaved,
            Map<Long, az.fitnest.order.grpc.PackageNameInfo> packageInfoMap,
            Map<Long, List<az.fitnest.catalog.model.entity.GymWorkHour>> workHoursMap,
            Map<String, String> translationLookup,
            String userLanguage) {

        double stars = gym.getRating() != null ? gym.getRating() : 0.0;
        boolean isNew = gym.getCreatedDate() != null
                && gym.getCreatedDate().isAfter(LocalDateTime.now().minusMonths(1L));

        Address address = gym.getAddress();

        Double distanceKm = null;
        if (userLat != null
                && userLng != null
                && address != null
                && address.getLatitude() != null
                && address.getLongitude() != null) {

            distanceKm = Math.round(
                    calculateDistanceRaw(
                            userLat, userLng,
                            address.getLatitude(), address.getLongitude()
                    ) * 10.0
            ) / 10.0;
        }

        List<CategoryResponse> categoryDtos = new java.util.ArrayList<>();
        if (gym.getCategory() != null) {
            Category c = gym.getCategory();
            String localizedCatName = getTranslatedValueCached(translationLookup,
                    "CATEGORY", c.getCategoryId().toString(), "name", userLanguage);
            categoryDtos.add(CategoryResponse.builder()
                    .id(c.getCategoryId())
                    .name(localizedCatName != null && !localizedCatName.isEmpty() ? localizedCatName : c.getName())
                    .photoUrl(c.getPhotoUrl())
                    .iconUrl(c.getIconUrl())
                    .coverImageUrl(c.getPhotoUrl())
                    .build());
        }
        if (gym.getSubCategory() != null) {
            Category c = gym.getSubCategory();
            String localizedCatName = getTranslatedValueCached(translationLookup,
                    "CATEGORY", c.getCategoryId().toString(), "name", userLanguage);
            categoryDtos.add(CategoryResponse.builder()
                    .id(c.getCategoryId())
                    .name(localizedCatName != null && !localizedCatName.isEmpty() ? localizedCatName : c.getName())
                    .photoUrl(c.getPhotoUrl())
                    .iconUrl(c.getIconUrl())
                    .coverImageUrl(c.getPhotoUrl())
                    .build());
        }

        List<GymPlanItemResponseV2> supportedSubscriptions = new java.util.ArrayList<>();
        if (gym.getSubscriptions() != null && !gym.getSubscriptions().isEmpty()) {
            supportedSubscriptions = gym.getSubscriptions().stream()
                    .filter(sub -> sub.getPackageId() != null)
                    .map(sub -> {
                        az.fitnest.order.grpc.PackageNameInfo info = packageInfoMap.get(sub.getPackageId());
                        String planId = sub.getPackageId().toString();
                        String localizedPackageName = getTranslatedValueCached(translationLookup,
                                "GYMSUBSCRIPTION", planId, "name", userLanguage);
                        String packageName = cleanPackageName(
                                (localizedPackageName != null && !localizedPackageName.isEmpty())
                                        ? localizedPackageName
                                        : (info != null ? info.getName() : null)
                        );
                        List<GymPlanBenefitResponse> benefitsList = sub.getSupportedServices().stream()
                                .map(b -> {
                                    String localizedBenefit = getTranslatedValueCached(translationLookup,
                                            "SUPPORTEDSERVICE", b.getId().toString(), "name", userLanguage);
                                    return GymPlanBenefitResponse.builder()
                                            .description(localizedBenefit != null && !localizedBenefit.isEmpty()
                                                    ? localizedBenefit : b.getName())
                                            .iconImageUrl(b.getIconUrl())
                                            .build();
                                })
                                .toList();
                        return GymPlanItemResponseV2.builder()
                                .plan_id(planId)
                                .packageName(packageName)
                                .categoryId(sub.getCategory() != null ? sub.getCategory().getId() : null)
                                .dailyPrice(sub.getDailyPrice())
                                .benefits(benefitsList)
                                .build();
                    })
                    .toList();
        }

        String localizedName = getLocalizedGymName(gym, userLanguage);

        List<az.fitnest.catalog.model.entity.GymWorkHour> gymWorkHours = workHoursMap.getOrDefault(gym.getId(), java.util.Collections.emptyList());
        String workHoursText = az.fitnest.catalog.mapper.GymMapper.toWorkHoursText(gymWorkHours, userLanguage);

        String city = null;
        String location = null;
        if (address != null) {
            city = getTranslatedValueCached(translationLookup, "GYM", gym.getId().toString(), "city", userLanguage);
            if (city == null || city.isEmpty()) city = address.getCity();
            location = getTranslatedValueCached(translationLookup, "GYM", gym.getId().toString(), "addressText", userLanguage);
            if (location == null || location.isEmpty()) location = address.getAddressText();
        }

        return GymMainPageResponseV2.builder()
                .gymId(gym.getId().toString())
                .name(localizedName)
                .coverImageUrl(gym.getCoverImageUrl())
                .stars(stars)
                .isNew(isNew)
                .location(location)
                .city(city)
                .distanceKm(distanceKm)
                .isSaved(isSaved)
                .categories(categoryDtos)
                .supportedSubscriptions(supportedSubscriptions)
                .workHoursText(workHoursText)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public GymInfoAdminResponseV2 getGymDetailsAdminV2(Long gymId) {
        verifyGymOwnership(gymId);
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));

        String userLanguage = resolveUserLanguage();

        List<CategoryResponse> mainCategoryDtos = new java.util.ArrayList<>();
        if (gym.getMainCategories() != null) {
            for (Category c : gym.getMainCategories()) {
                String localizedCatName = translationService.getTranslatedValue("CATEGORY", c.getId().toString(), "name", userLanguage);
                mainCategoryDtos.add(CategoryResponse.builder()
                        .id(c.getId())
                        .name(localizedCatName != null && !localizedCatName.isEmpty() ? localizedCatName : c.getName())
                        .photoUrl(c.getPhotoUrl())
                        .iconUrl(c.getIconUrl())
                        .coverImageUrl(c.getPhotoUrl())
                        .lessonTypes(mapCategoryLessonTypes(c, userLanguage))
                        .build());
            }
        }

        List<CategoryResponse> subCategoryDtos = new java.util.ArrayList<>();
        if (gym.getSubCategories() != null) {
            for (Category c : gym.getSubCategories()) {
                String localizedCatName = translationService.getTranslatedValue("CATEGORY", c.getId().toString(), "name", userLanguage);
                subCategoryDtos.add(CategoryResponse.builder()
                        .id(c.getId())
                        .name(localizedCatName != null && !localizedCatName.isEmpty() ? localizedCatName : c.getName())
                        .photoUrl(c.getPhotoUrl())
                        .iconUrl(c.getIconUrl())
                        .coverImageUrl(c.getPhotoUrl())
                        .lessonTypes(mapCategoryLessonTypes(c, userLanguage))
                        .build());
            }
        }

        List<CategoryResponse> categoryDtos = new java.util.ArrayList<>();
        categoryDtos.addAll(mainCategoryDtos);
        categoryDtos.addAll(subCategoryDtos);

        CategoryResponse mainCategoryDto = mainCategoryDtos.isEmpty() ? null : mainCategoryDtos.get(0);
        CategoryResponse subCategoryDto = subCategoryDtos.isEmpty() ? null : subCategoryDtos.get(0);

        List<az.fitnest.catalog.dto.response.RoomImageDtoV2> roomDtos = new java.util.ArrayList<>();
        if (gym.getRooms() != null) {
            for (az.fitnest.catalog.model.entity.Room room : gym.getRooms()) {
                String imgUrl = room.getImages() != null && !room.getImages().isEmpty()
                        ? room.getImages().stream().findFirst().map(RoomImage::getPictureUrl).orElse(null)
                        : null;
                roomDtos.add(az.fitnest.catalog.dto.response.RoomImageDtoV2.builder()
                        .id(room.getId())
                        .name(room.getName())
                        .imageUrl(imgUrl)
                        .categoryId(room.getCategory() != null ? room.getCategory().getId() : null)
                        .build());
            }
        }

        String city = null;
        String addressText = null;
        Double lat = null;
        Double lng = null;
        Double altitude = null;
        if (gym.getAddress() != null) {
            city = getLocalizedAddressField(gym.getId(), "GYM", gym.getAddress(), "city", userLanguage);
            addressText = getLocalizedAddressField(gym.getId(), "GYM", gym.getAddress(), "addressText", userLanguage);
            lat = gym.getAddress().getLatitude();
            lng = gym.getAddress().getLongitude();
            altitude = gym.getAddress().getAltitude();
        }

        String created = "";
        if (gym.getCreatedDate() != null) {
            created = gym.getCreatedDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        }

        java.util.Set<az.fitnest.catalog.model.entity.LessonType> gymCategoryLessonTypes = new java.util.LinkedHashSet<>();
        if (gym.getMainCategories() != null) {
            for (Category c : gym.getMainCategories()) {
                if (c.getLessonTypes() != null) {
                    gymCategoryLessonTypes.addAll(c.getLessonTypes());
                }
            }
        }
        if (gym.getSubCategories() != null) {
            for (Category c : gym.getSubCategories()) {
                if (c.getLessonTypes() != null) {
                    gymCategoryLessonTypes.addAll(c.getLessonTypes());
                }
            }
        }
        List<LessonTypeResponse> lessonTypes = gymCategoryLessonTypes.stream()
                .map(lt -> {
                    String localizedLt = translationService.getTranslatedValue("LessonType", lt.getId().toString(), "name", userLanguage);
                    return new LessonTypeResponse(lt.getId(), localizedLt != null ? localizedLt : lt.getName());
                })
                .toList();

        String localizedGymName = translationService.getTranslatedValue("GYM", gym.getId().toString(), "name", userLanguage);
        if (localizedGymName == null || localizedGymName.isEmpty()) {
            localizedGymName = gym.getName();
        }

        String localizedGymDescription = translationService.getTranslatedValue("GYM", gym.getId().toString(), "description", userLanguage);
        if (localizedGymDescription == null || localizedGymDescription.isEmpty()) {
            localizedGymDescription = gym.getDescription();
        }

        List<GymDescriptionResponse> descriptions = new java.util.ArrayList<>();
        if (gym.getDescriptions() != null) {
            for (GymDescription desc : gym.getDescriptions()) {
                String localizedCatName = translationService.getTranslatedValue("CATEGORY", desc.getCategory().getId().toString(), "name", userLanguage);
                String localizedCatDesc = translationService.getTranslatedValue("GYM", gym.getId().toString(), "description_" + desc.getCategory().getId(), userLanguage);
                descriptions.add(new GymDescriptionResponse(
                        desc.getCategory().getId(),
                        localizedCatName != null && !localizedCatName.isEmpty() ? localizedCatName : desc.getCategory().getName(),
                        desc.getPhone(),
                        localizedCatDesc != null && !localizedCatDesc.isEmpty() ? localizedCatDesc : desc.getDescription(),
                        desc.getCoverImageUrl()
                ));
            }
        }

        return GymInfoAdminResponseV2.builder()
                .id(gym.getId())
                .categories(categoryDtos)
                .mainCategories(mainCategoryDtos)
                .subCategories(subCategoryDtos)
                .category(mainCategoryDto)
                .subCategory(subCategoryDto)
                .descriptions(descriptions)
                .name(localizedGymName)
                .description(localizedGymDescription)
                .coverImageUrl(gym.getCoverImageUrl())
                .rooms(roomDtos)
                .phone(gym.getPhone())
                .email(gym.getEmail())
                .city(city)
                .address(addressText)
                .latitude(lat)
                .longitude(lng)
                .altitude(altitude)
                .status(gym.getStatus())
                .createdAt(created)
                .hasSubcategories(gym.getHasSubcategories())
                .lessonTypes(lessonTypes)
                .build();
    }

    private List<LessonTypeResponse> mapCategoryLessonTypes(Category category, String userLanguage) {
        if (category.getLessonTypes() == null) {
            return java.util.List.of();
        }
        return category.getLessonTypes().stream()
                .map(lt -> {
                    String localizedLt = translationService.getTranslatedValue("LessonType", lt.getId().toString(), "name", userLanguage);
                    return new LessonTypeResponse(lt.getId(), localizedLt != null ? localizedLt : lt.getName());
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public GymSubscriptionsAdminResponseV2 getGymSubscriptionsV2(Long gymId) {
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));

        String userLanguage = resolveUserLanguage();
        List<az.fitnest.catalog.dto.response.GymPlanItemAdminResponseV2> subscriptions = new java.util.ArrayList<>();

        if (gym.getSubscriptions() != null && !gym.getSubscriptions().isEmpty()) {
            try {
                List<Long> packageIds = gym.getSubscriptions().stream()
                        .map(sub -> sub.getPackageId())
                        .filter(java.util.Objects::nonNull)
                        .toList();

                List<az.fitnest.order.grpc.PackageNameInfo> packageInfos = orderServiceGrpcClient
                        .getPackageNamesByIds(packageIds);
                java.util.Map<Long, az.fitnest.order.grpc.PackageNameInfo> idToInfo = packageInfos.stream()
                        .collect(java.util.stream.Collectors.toMap(
                                az.fitnest.order.grpc.PackageNameInfo::getPackageId,
                                p -> p));

                subscriptions = gym.getSubscriptions().stream()
                        .filter(sub -> sub.getPackageId() != null)
                        .filter(sub -> idToInfo.get(sub.getPackageId()) != null)
                        .map(sub -> {
                            az.fitnest.order.grpc.PackageNameInfo info = idToInfo.get(sub.getPackageId());
                            String localizedName = cleanPackageName(info.getName());

                            List<az.fitnest.catalog.dto.response.GymPlanBenefitAdminResponse> benefits = sub
                                    .getSupportedServices().stream()
                                    .map(b -> {
                                        String localizedBenefit = translationService.getTranslatedValue(
                                                "SUPPORTEDSERVICE", b.getId().toString(), "name", userLanguage);
                                        return az.fitnest.catalog.dto.response.GymPlanBenefitAdminResponse.builder()
                                                .id(b.getId())
                                                .name(localizedBenefit != null && !localizedBenefit.isEmpty()
                                                        ? localizedBenefit
                                                        : b.getName())
                                                .iconImageUrl(b.getIconUrl())
                                                .build();
                                    }).collect(java.util.stream.Collectors.toList());

                            return az.fitnest.catalog.dto.response.GymPlanItemAdminResponseV2.builder()
                                    .packageId(sub.getPackageId())
                                    .packageName(localizedName)
                                    .categoryId(sub.getCategory() != null ? sub.getCategory().getId() : null)
                                    .dailyPrice(sub.getDailyPrice())
                                    .benefits(benefits)
                                    .build();
                        }).collect(java.util.stream.Collectors.toList());
            } catch (Exception e) {
            }
        }

        return GymSubscriptionsAdminResponseV2.builder()
                .gymId(gymId)
                .subscriptions(subscriptions)
                .build();
    }
}

