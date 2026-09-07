package az.fitnest.catalog.service.impl;

import az.fitnest.catalog.client.IdentityServiceGrpcClient;
import az.fitnest.catalog.client.NotificationsServiceGrpcClient;
import az.fitnest.catalog.client.OrderServiceGrpcClient;
import az.fitnest.catalog.client.UserServiceGrpcClient;
import az.fitnest.catalog.dto.request.GymAdminCreateRequest;
import az.fitnest.catalog.dto.request.GymAdminUpdateRequest;
import az.fitnest.catalog.dto.request.GymCreateCompleteRequest;
import az.fitnest.catalog.dto.request.GymCreateCompleteRequestV2;
import az.fitnest.catalog.dto.request.GymCreateStep1Request;
import az.fitnest.catalog.dto.request.GymCreateStep1RequestV2;
import az.fitnest.catalog.dto.request.GymCreateStep2Request;
import az.fitnest.catalog.dto.request.GymCreateStep3Request;
import az.fitnest.catalog.dto.request.GymCreateStep6Request;
import az.fitnest.catalog.dto.request.GymCreateStep6RequestV2;
import az.fitnest.catalog.dto.request.GymCreateStep6SubscriptionRequest;
import az.fitnest.catalog.dto.request.GymCreateStep6SubscriptionRequestV2;
import az.fitnest.catalog.dto.request.GymCreateStep7Request;
import az.fitnest.catalog.dto.request.GymRequest;
import az.fitnest.catalog.dto.request.GymSubscriptionBenefitsUpdateRequest;
import az.fitnest.catalog.dto.request.SupportedServiceRequest;
import az.fitnest.catalog.dto.response.CheckInResponse;
import az.fitnest.catalog.dto.response.GeocodingResponse;
import az.fitnest.catalog.dto.response.GymCreateStep1Response;
import az.fitnest.catalog.dto.response.GymWorkHourResponse;
import az.fitnest.catalog.dto.response.SupportedServiceResponse;
import az.fitnest.catalog.exception.BadRequestException;
import az.fitnest.catalog.exception.ForbiddenException;
import az.fitnest.catalog.exception.ResourceNotFoundException;
import az.fitnest.catalog.model.entity.Address;
import az.fitnest.catalog.model.entity.Category;
import az.fitnest.catalog.model.entity.Gym;
import az.fitnest.catalog.model.entity.GymImage;
import az.fitnest.catalog.model.entity.GymSubscription;
import az.fitnest.catalog.model.entity.GymWorkHour;
import az.fitnest.catalog.model.entity.Reservation;
import az.fitnest.catalog.model.entity.Room;
import az.fitnest.catalog.model.entity.RoomImage;
import az.fitnest.catalog.model.entity.SavedGym;
import az.fitnest.catalog.model.entity.SupportedService;
import az.fitnest.catalog.model.entity.Trainer;
import az.fitnest.catalog.model.entity.TrainerReservationDate;
import az.fitnest.catalog.model.enums.GymStatus;
import az.fitnest.catalog.model.enums.GymWorkHourPeriod;
import az.fitnest.catalog.dto.request.CategoryDetail;
import az.fitnest.catalog.model.entity.GymDescription;
import az.fitnest.catalog.repository.GymDescriptionRepository;
import az.fitnest.catalog.repository.CategoryRepository;
import az.fitnest.catalog.repository.GymAdminRepository;
import az.fitnest.catalog.repository.GymEntranceHistoryRepository;
import az.fitnest.catalog.repository.GymImageRepository;
import az.fitnest.catalog.repository.GymRepository;
import az.fitnest.catalog.repository.ReservationRepository;
import az.fitnest.catalog.repository.SavedGymRepository;
import az.fitnest.catalog.repository.SupportedServiceRepository;
import az.fitnest.catalog.repository.TrainerRepository;
import az.fitnest.catalog.repository.TrainerReservationDateRepository;
import az.fitnest.catalog.service.FileStorageService;
import az.fitnest.catalog.service.GymQrCodeService;
import az.fitnest.catalog.service.GymTrainerService;
import az.fitnest.catalog.service.GymWriteService;
import az.fitnest.catalog.service.ReverseGeocodingService;
import az.fitnest.catalog.util.PhoneUtil;
import az.fitnest.catalog.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GymWriteServiceImpl implements GymWriteService {
    private final GymRepository gymRepository;
    private final GymDescriptionRepository gymDescriptionRepository;
    private final SavedGymRepository savedGymRepository;
    private final CategoryRepository categoryRepository;
    private final ReverseGeocodingService reverseGeocodingService;
    private final FileStorageService fileStorageService;
    private final OrderServiceGrpcClient orderServiceGrpcClient;
    private final GymImageRepository gymImageRepository;
    private final SupportedServiceRepository supportedServiceRepository;
    private final IdentityServiceGrpcClient identityServiceGrpcClient;
    private final TrainerRepository trainerRepository;
    private final az.fitnest.catalog.repository.LessonTypeRepository lessonTypeRepository;
    private final TrainerReservationDateRepository trainerReservationDateRepository;
    private final GymAdminRepository gymAdminRepository;
    private final ReservationRepository reservationRepository;
    private final GymTrainerService gymTrainerService;
    private final GymQrCodeService gymQrCodeService;
    private final GymEntranceHistoryRepository gymEntranceHistoryRepository;
    private final az.fitnest.catalog.service.TranslationService translationService;
    private final Executor imageUploadExecutor;
    private final NotificationsServiceGrpcClient notificationsServiceClient;
    private final UserServiceGrpcClient userServiceGrpcClient;
    private final az.fitnest.catalog.service.GymActivationNotifier gymActivationNotifier;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private record RoomImageUploadResult(String roomName, String url) {
    }

    private record GymAdminCreateResult(GymAdminCreateRequest req, Long userId) {
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = "main-page-gyms", allEntries = true),
            @CacheEvict(cacheNames = "gym-listings", allEntries = true),
            @CacheEvict(cacheNames = "admin-gyms", allEntries = true)
    })
    public void createGym(GymRequest request) {
        GeocodingResponse geocoding = reverseGeocodingService.reverseGeocode(request.address().latitude(), request.address().longitude());
        saveGymInternal(request, geocoding);
    }

    @Transactional
    protected void saveGymInternal(GymRequest request, GeocodingResponse geocoding) {
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new BadRequestException("INVALID_CATEGORY", "error.invalid_category"));
        Gym gym = new Gym();
        gym.setName(request.name());
        gym.setDescription(request.description());

        Address address = new Address();
        address.setLatitude(request.address().latitude());
        address.setLongitude(request.address().longitude());
        address.setAltitude(request.address().altitude());
        if (geocoding != null) {
            address.setAddressText(geocoding.addressText());
            address.setCity(geocoding.city());
        }
        gym.setAddress(address);

        gym.setPhone(PhoneUtil.normalize(request.phone()));
        gym.setEmail(request.email() != null && request.email().isBlank() ? null : request.email());
        gym.setCategory(category);

        updateWorkHours(gym.getGeneralWorkHours(), request.generalWorkHours());
        updateWorkHours(gym.getWorkHoursWoman(), request.workHoursWoman());
        updateWorkHours(gym.getWorkHoursMan(), request.workHoursMan());

        if (request.restDays() != null) {
            Set<GymWorkHourPeriod> restDays = request.restDays().stream()
                    .flatMap(r -> az.fitnest.catalog.mapper.GymMapper.expandPeriods(r.period()).stream())
                    .collect(java.util.stream.Collectors.toSet());

            validateNoWorkHoursOnRestDays(request.generalWorkHours(), restDays, "general");
            validateNoWorkHoursOnRestDays(request.workHoursWoman(), restDays, "woman");
            validateNoWorkHoursOnRestDays(request.workHoursMan(), restDays, "man");

            gym.setRestDays(restDays);
        }

        gym.setStatus(request.status() != null ? request.status() : GymStatus.ACTIVE);

        Gym saved = gymRepository.save(gym);

        TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        gymQrCodeService.generateAndSaveQrCode(saved.getId());
                        if (saved.getStatus() == GymStatus.ACTIVE) {
                            gymActivationNotifier.notifyNewActiveGym(saved.getId(), saved.getName());
                        }
                    }
                }
        );
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = "gym-detail", key = "#gymId"),
            @CacheEvict(cacheNames = "admin-gyms", allEntries = true),
            @CacheEvict(cacheNames = "gym-listings", allEntries = true),
            @CacheEvict(cacheNames = "main-page-gyms", allEntries = true)
    })
    public void updateGym(Long gymId, GymRequest request) {
        GeocodingResponse geocoding = reverseGeocodingService.reverseGeocode(request.address().latitude(), request.address().longitude());
        updateGymInternal(gymId, request, geocoding);
    }

    @Transactional
    protected void updateGymInternal(Long gymId, GymRequest request, GeocodingResponse geocoding) {
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new BadRequestException("INVALID_CATEGORY", "error.invalid_category"));

        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));

        gym.setName(request.name());
        gym.setDescription(request.description());

        Address address = gym.getAddress();
        if (address == null) {
            address = new Address();
            gym.setAddress(address);
        }
        address.setLatitude(request.address().latitude());
        address.setLongitude(request.address().longitude());
        address.setAltitude(request.address().altitude());
        if (geocoding != null) {
            address.setAddressText(geocoding.addressText());
            address.setCity(geocoding.city());
        }

        gym.setPhone(PhoneUtil.normalize(request.phone()));
        gym.setEmail(request.email() != null && request.email().isBlank() ? null : request.email());
        gym.setCategory(category);

        updateWorkHours(gym.getGeneralWorkHours(), request.generalWorkHours());
        updateWorkHours(gym.getWorkHoursWoman(), request.workHoursWoman());
        updateWorkHours(gym.getWorkHoursMan(), request.workHoursMan());

        if (request.restDays() != null) {
            Set<GymWorkHourPeriod> restDays = request.restDays().stream()
                    .flatMap(r -> az.fitnest.catalog.mapper.GymMapper.expandPeriods(r.period()).stream())
                    .collect(java.util.stream.Collectors.toSet());

            validateNoWorkHoursOnRestDays(request.generalWorkHours(), restDays, "general");
            validateNoWorkHoursOnRestDays(request.workHoursWoman(), restDays, "woman");
            validateNoWorkHoursOnRestDays(request.workHoursMan(), restDays, "man");

            gym.getRestDays().clear();
            gym.getRestDays().addAll(restDays);
        }

        gym.setStatus(request.status() != null ? request.status() : GymStatus.ACTIVE);

        gymRepository.save(gym);

        translationService.autoTranslateAndSave("GYM", gym.getId().toString(), "name", request.name());
        translationService.autoTranslateAndSave("GYM", gym.getId().toString(), "description", request.description());
        if (gym.getAddress() != null) {
            translationService.autoTranslateAndSave("GYM", gym.getId().toString(), "addressText", gym.getAddress().getAddressText());
            translationService.autoTranslateAndSave("GYM", gym.getId().toString(), "city", gym.getAddress().getCity());
        }
    }

    @Transactional
    @CacheEvict(cacheNames = "gym-detail", key = "#gymId")
    public void enableGymSubscription(Long gymId, Long subscriptionId) {
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        if (!orderServiceGrpcClient.checkPackageExists(subscriptionId)) {
            throw new BadRequestException("PACKAGE_NOT_FOUND", "error.package_not_found");
        }
        gym.getSubscriptions().removeIf(s -> s.getPackageId() != null && s.getPackageId().equals(subscriptionId));
        GymSubscription subscription = new GymSubscription();
        subscription.setGym(gym);
        subscription.setPackageId(subscriptionId);
        subscription.setSupportedServices(new HashSet<>());
        gym.getSubscriptions().add(subscription);
        gymRepository.save(gym);
    }

    @Transactional
    @CacheEvict(cacheNames = "gym-detail", key = "#gymId")
    public void updateGymSubscriptionBenefits(Long gymId, Long packageId, GymSubscriptionBenefitsUpdateRequest request) {
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        GymSubscription subscription = gym.getSubscriptions().stream()
                .filter(sub -> sub.getPackageId() != null && sub.getPackageId().equals(packageId))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("SUBSCRIPTION_NOT_ENABLED", "error.subscription_not_enabled"));

        if (request.benefitIds() != null) {
            List<SupportedService> services = supportedServiceRepository.findAllById(request.benefitIds()).stream()
                    .filter(s -> s.getGymId() == null || s.getGymId().equals(gymId))
                    .toList();
            subscription.setSupportedServices(new HashSet<>(services));
        }

        gymRepository.save(gym);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = "gym-detail", key = "#gymId"),
            @CacheEvict(cacheNames = "admin-gyms", allEntries = true),
            @CacheEvict(cacheNames = "gym-listings", allEntries = true),
            @CacheEvict(cacheNames = "main-page-gyms", allEntries = true)
    })
    public void deleteGym(Long gymId) {
        Gym gym = gymRepository.findById(gymId).orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));

        List<String> dependencies = new ArrayList<>();
        if (savedGymRepository.existsByGymId(gymId)) {
            dependencies.add("error.gym_dependency_saved");
        }
        if (reservationRepository.existsByGymId(gymId)) {
            dependencies.add("error.gym_dependency_reservations");
        }

        if (!dependencies.isEmpty()) {
            throw new az.fitnest.catalog.exception.GymDependencyException(dependencies);
        }

        gymEntranceHistoryRepository.deleteByGymId(gymId);
        supportedServiceRepository.deleteSubscriptionAssociationsByGymId(gymId);
        supportedServiceRepository.deleteAllByGymId(gymId);
        gymAdminRepository.deleteAllByGymId(gymId);

        List<String> filesToDelete = new ArrayList<>();
        if (gym.getCoverImageUrl() != null) filesToDelete.add(gym.getCoverImageUrl());
        if (gym.getQrCodeUrl() != null) filesToDelete.add(gym.getQrCodeUrl());

        if (gym.getImages() != null) {
            filesToDelete.addAll(gym.getImages().stream().map(GymImage::getUrl).toList());
        }

        if (gym.getTrainers() != null) {
            filesToDelete.addAll(gym.getTrainers().stream().map(Trainer::getPicture).filter(Objects::nonNull).toList());
        }

        if (gym.getRooms() != null) {
            filesToDelete.addAll(gym.getRooms().stream()
                    .flatMap(r -> r.getImages().stream())
                    .map(RoomImage::getPictureUrl)
                    .toList());
        }

        gymRepository.delete(gym);

        fileStorageService.deleteFilesAfterCommit(filesToDelete);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = "gym-detail", key = "T(az.fitnest.catalog.util.UserContext).extractUserId(#principal) + '_' + #gymId + '_AZ'"),
            @CacheEvict(cacheNames = "gym-detail", key = "T(az.fitnest.catalog.util.UserContext).extractUserId(#principal) + '_' + #gymId + '_EN'"),
            @CacheEvict(cacheNames = "gym-detail", key = "T(az.fitnest.catalog.util.UserContext).extractUserId(#principal) + '_' + #gymId + '_RU'"),
            @CacheEvict(cacheNames = "gym-listings", allEntries = true),
            @CacheEvict(cacheNames = "main-page-gyms", allEntries = true)
    })
    public boolean toggleSave(Object principal, Long gymId) {
        Long userId = UserContext.extractUserId(principal);
        if (userId == null) throw new ForbiddenException("error.unauthorized", "UNAUTHORIZED");

        Optional<SavedGym> existing = savedGymRepository.findByUserIdAndGymId(userId, gymId);
        if (existing.isPresent()) {
            savedGymRepository.delete(existing.get());
            return false;
        } else {
            Gym gym = gymRepository.findById(gymId).orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
            SavedGym saved = new SavedGym();
            saved.setUserId(userId);
            saved.setGym(gym);
            savedGymRepository.save(saved);
            return true;
        }
    }

    public CheckInResponse checkIn(Object principal, Long gymId) {
        Long userId = UserContext.extractUserId(principal);
        if (userId == null) throw new ForbiddenException("error.unauthorized", "UNAUTHORIZED");

        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));

        // Check if there is an active reservation at this gym today
        List<Reservation> activeReservations = reservationRepository.findActiveReservationsForCheckIn(
                userId, gymId, java.time.LocalDate.now(), java.time.LocalTime.now(),
                java.util.List.of(az.fitnest.catalog.model.enums.ReservationStatus.APPROVED, az.fitnest.catalog.model.enums.ReservationStatus.PENDING)
        );

        boolean consumeFrozen = false;
        Reservation targetReservation = null;
        if (!activeReservations.isEmpty()) {
            consumeFrozen = true;
            targetReservation = activeReservations.get(0);
        }

        orderServiceGrpcClient.checkIn(userId, gymId, consumeFrozen);

        if (targetReservation != null) {
            targetReservation.setAttended(true);
            if (targetReservation.getStatus() == az.fitnest.catalog.model.enums.ReservationStatus.PENDING) {
                targetReservation.setStatus(az.fitnest.catalog.model.enums.ReservationStatus.APPROVED);
                targetReservation.setApprovedAt(LocalDateTime.now());
            }
            reservationRepository.save(targetReservation);
        }

        String addressText = gym.getAddress() != null ? gym.getAddress().getAddressText() : null;
        LocalDateTime now = LocalDateTime.now();

        return new CheckInResponse(addressText, now.toLocalDate(), now.toLocalTime());
    }

    @CacheEvict(cacheNames = "gym-images", key = "#gymId")
    public void addRoomImages(Long gymId, List<String> roomNames, List<MultipartFile> files) {
        if (roomNames.size() != files.size()) {
            throw new BadRequestException("INVALID_INPUT", "error.invalid_input");
        }

        List<CompletableFuture<RoomImageUploadResult>> futures = new ArrayList<>();

        for (int i = 0; i < files.size(); i++) {
            MultipartFile originalFile = files.get(i);
            if (originalFile == null || originalFile.isEmpty()) continue;

            String roomName = roomNames.get(i);
            MultipartFile validatedFile = fileStorageService.validateAndWrapImage(originalFile);

            futures.add(CompletableFuture.supplyAsync(() -> {
                String url = fileStorageService.saveFile(validatedFile, "/gyms/rooms");
                return new RoomImageUploadResult(roomName, url);
            }, imageUploadExecutor));
        }

        List<RoomImageUploadResult> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        if (!results.isEmpty()) {
            new TransactionTemplate(transactionManager).execute(status -> {
                applyRoomImagesUpdateInternal(gymId, results);
                return null;
            });
        }
    }

    @Transactional
    protected void applyRoomImagesUpdateInternal(Long gymId, List<RoomImageUploadResult> results) {
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));

        for (RoomImageUploadResult res : results) {
            Room room = Room.builder()
                    .name(res.roomName())
                    .gym(gym)
                    .build();
            gym.getRooms().add(room);

            RoomImage roomImage = RoomImage.builder()
                    .room(room)
                    .pictureUrl(res.url())
                    .build();

            room.getImages().add(roomImage);
        }
        gymRepository.save(gym);
    }

    @Transactional
    @CacheEvict(cacheNames = "gym-images", key = "#gymId")
    public void deleteAllGymRooms(Long gymId) {
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));

        if (gym.getRooms() != null && !gym.getRooms().isEmpty()) {
            List<String> roomImageUrls = gym.getRooms().stream()
                    .flatMap(r -> r.getImages().stream())
                    .map(RoomImage::getPictureUrl)
                    .filter(url -> url != null && !url.isBlank())
                    .toList();

            if (!roomImageUrls.isEmpty()) {
                fileStorageService.deleteFilesAfterCommit(roomImageUrls);
            }
            gym.getRooms().clear();
            gymRepository.save(gym);
        }
    }

    @Transactional
    @CacheEvict(cacheNames = "gym-images", key = "#gymId")
    public void deleteGymRoomById(Long gymId, Long roomId) {
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));

        Room room = gym.getRooms().stream()
                .filter(r -> r.getId().equals(roomId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("ROOM_NOT_FOUND", "error.room_not_found"));

        List<String> roomImageUrls = room.getImages().stream()
                .map(RoomImage::getPictureUrl)
                .filter(url -> url != null && !url.isBlank())
                .toList();

        if (!roomImageUrls.isEmpty()) {
            fileStorageService.deleteFilesAfterCommit(roomImageUrls);
        }

        gym.getRooms().remove(room);
        gymRepository.save(gym);
    }

    @Transactional
    @CacheEvict(cacheNames = "gym-images", key = "#gymId")
    public void deleteRoomImageById(Long gymId, Long imageId) {
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));

        for (Room room : gym.getRooms()) {
            Optional<RoomImage> roomImageOpt = room.getImages().stream()
                    .filter(img -> img.getId().equals(imageId))
                    .findFirst();

            if (roomImageOpt.isPresent()) {
                RoomImage roomImage = roomImageOpt.get();
                if (roomImage.getPictureUrl() != null && !roomImage.getPictureUrl().isBlank()) {
                    fileStorageService.deleteFilesAfterCommit(List.of(roomImage.getPictureUrl()));
                }
                room.getImages().remove(roomImage);
                gymRepository.save(gym);
                return;
            }
        }
        throw new ResourceNotFoundException("ROOM_IMAGE_NOT_FOUND", "error.room_image_not_found");
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = "gym-detail", key = "#gymId"),
            @CacheEvict(cacheNames = "gymDetails", key = "#gymId")
    })
    public void updateRoomName(Long gymId, Long roomId, String name) {
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        Room room = gym.getRooms().stream()
                .filter(r -> r.getId().equals(roomId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("ROOM_NOT_FOUND", "error.room_not_found"));
        room.setName(name);
        gymRepository.save(gym);
    }

    @CacheEvict(cacheNames = "gym-detail", key = "#gymId")
    public void updateCoverImage(Long gymId, MultipartFile coverPhoto) {
        MultipartFile validatedFile = fileStorageService.validateAndWrapImage(coverPhoto);
        String url = fileStorageService.saveFile(validatedFile, "/gyms/covers");
        updateCoverImageInternal(gymId, url);
    }

    @Transactional
    protected void updateCoverImageInternal(Long gymId, String url) {
        Gym gym = gymRepository.findById(gymId).orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        if (gym.getCoverImageUrl() != null) fileStorageService.deleteFilesAfterCommit(List.of(gym.getCoverImageUrl()));
        gym.setCoverImageUrl(url);
        gymRepository.save(gym);
    }

    @Transactional
    public void deleteAllGyms() {
        List<String> filesToDelete = new ArrayList<>();
        try {
            filesToDelete.addAll(gymRepository.findAllGymRelatedFileUrls());
        } catch (Exception e) {
        }

        gymAdminRepository.deleteAllInBatch();
        savedGymRepository.deleteAllInBatch();

        entityManager.createNativeQuery("DELETE FROM room_images").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM rooms").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM gym_entrance_history").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM gym_subscriptions").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM reviews").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM trainers").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM gym_images").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM gym_social_links").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM gym_general_work_hours").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM gym_work_hours_woman").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM gym_work_hours_man").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM gym_rest_days").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM gyms").executeUpdate();

        if (!filesToDelete.isEmpty()) {
            fileStorageService.deleteFilesAfterCommit(filesToDelete);
        }
    }

    @Override
    @Transactional
    public void deleteGymEntranceHistory() {
        gymEntranceHistoryRepository.deleteAllInBatch();
    }

    @Override
    @Transactional
    public void deleteGymEntranceHistory(Long gymId, Long packageId) {
        if (packageId == null || packageId == 0) {
            gymEntranceHistoryRepository.deleteByGymIdAndPackageIdIsNull(gymId);
        } else {
            gymEntranceHistoryRepository.deleteByGymIdAndPackageId(gymId, packageId);
        }
    }

    @Override
    @Transactional
    public void deleteGymEntranceHistoryByIds(Long gymId, List<Long> ids) {
        if (!gymRepository.existsById(gymId)) {
            throw new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found");
        }
        if (ids == null || ids.isEmpty()) {
            return;
        }
        gymEntranceHistoryRepository.deleteByIdInAndGymId(ids, gymId);
    }

    @Transactional
    @CacheEvict(cacheNames = "gym-detail", key = "#gymId")
    public void deleteAllGymSubscriptions(Long gymId) {
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        gym.getSubscriptions().clear();
        gymRepository.save(gym);
    }

    @Transactional
    @CacheEvict(cacheNames = "gym-detail", key = "#gymId")
    public void deleteGymSubscriptionById(Long gymId, Long subscriptionId) {
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        boolean removed = gym.getSubscriptions().removeIf(s -> s.getId().equals(subscriptionId));
        if (!removed) {
            throw new ResourceNotFoundException("SUBSCRIPTION_NOT_FOUND", "error.subscription_not_found");
        }
        gymRepository.save(gym);
    }

    @Transactional
    @CacheEvict(cacheNames = "gym-detail", key = "#gymId")
    public void toggleGymReservation(Long gymId, boolean enabled) {
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        gym.setIsReservationEnabled(enabled);
        gymRepository.save(gym);
    }

    @Transactional
    @CacheEvict(cacheNames = "admin-gyms", allEntries = true)
    public GymCreateStep1Response createGymStep1(GymCreateStep1Request request) {
        if (request.categoryId() == null) {
            throw new BadRequestException("CATEGORY_REQUIRED", "error.category_required");
        }
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("CATEGORY_NOT_FOUND", "error.category_not_found"));
        Gym gym = new Gym();
        gym.setName(request.name());
        gym.setDescription(request.description());
        gym.setPhone(PhoneUtil.normalize(request.phone()));
        gym.setEmail(request.email() != null && request.email().isBlank() ? null : request.email());
        gym.setCategory(category);
        gym.setStatus(GymStatus.DRAFT);
        gym.setCreationStep(1);
        gym = gymRepository.save(gym);

        translationService.autoTranslateAndSave("GYM", gym.getId().toString(), "name", request.name());
        translationService.autoTranslateAndSave("GYM", gym.getId().toString(), "description", request.description());

        return new GymCreateStep1Response(gym.getId());
    }

    @Transactional
    public void createGymStep2(Long id, List<String> names, List<String> surnames, List<Long> professionIds, List<String> emails, List<String> phones, List<MultipartFile> photos, List<String> lessonTypesPerTrainer) {
        Gym gym = gymRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        validateStep(gym, 1);

        gymTrainerService.addTrainers(id, names, surnames, professionIds, emails, phones, photos, lessonTypesPerTrainer);

        updateStep(gym, 1);
    }

    @Transactional
    public void createGymStep3(Long gymId, GymCreateStep2Request request) {
        Gym gym = gymRepository.findById(gymId).orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        validateStep(gym, 2);

        Set<GymWorkHourPeriod> restDays = new HashSet<>();
        if (request.restDays() != null) {
            restDays = request.restDays().stream()
                    .flatMap(r -> az.fitnest.catalog.mapper.GymMapper.expandPeriods(r.period()).stream())
                    .collect(java.util.stream.Collectors.toSet());
        }

        validateNoWorkHoursOnRestDays(request.generalWorkHours(), restDays, "general");
        validateNoWorkHoursOnRestDays(request.workHoursWoman(), restDays, "woman");
        validateNoWorkHoursOnRestDays(request.workHoursMan(), restDays, "man");

        updateWorkHours(gym.getGeneralWorkHours(), request.generalWorkHours());
        updateWorkHours(gym.getWorkHoursWoman(), request.workHoursWoman());
        updateWorkHours(gym.getWorkHoursMan(), request.workHoursMan());

        gym.getRestDays().clear();
        gym.getRestDays().addAll(restDays);

        updateStep(gym, 2);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = "gym-detail", key = "#gymId"),
            @CacheEvict(cacheNames = "gymDetails", allEntries = true),
            @CacheEvict(cacheNames = "admin-gyms", allEntries = true),
            @CacheEvict(cacheNames = "gym-listings", allEntries = true),
            @CacheEvict(cacheNames = "main-page-gyms", allEntries = true),
            @CacheEvict(cacheNames = "nearestGyms", allEntries = true)
    })
    public void updateGymWorkHours(Long gymId, GymCreateStep2Request request) {
        Gym gym = gymRepository.findById(gymId).orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));

        Set<GymWorkHourPeriod> restDays = new HashSet<>();
        if (request.restDays() != null) {
            restDays = request.restDays().stream()
                    .flatMap(r -> az.fitnest.catalog.mapper.GymMapper.expandPeriods(r.period()).stream())
                    .collect(java.util.stream.Collectors.toSet());
        }

        validateNoWorkHoursOnRestDays(request.generalWorkHours(), restDays, "general");
        validateNoWorkHoursOnRestDays(request.workHoursWoman(), restDays, "woman");
        validateNoWorkHoursOnRestDays(request.workHoursMan(), restDays, "man");

        updateWorkHours(gym.getGeneralWorkHours(), request.generalWorkHours());
        updateWorkHours(gym.getWorkHoursWoman(), request.workHoursWoman());
        updateWorkHours(gym.getWorkHoursMan(), request.workHoursMan());

        gym.getRestDays().clear();
        gym.getRestDays().addAll(restDays);

        gymRepository.save(gym);
    }

    private void validateNoWorkHoursOnRestDays(Set<GymWorkHourResponse> workHours, Set<GymWorkHourPeriod> restDays, String type) {
        if (workHours == null || restDays.isEmpty()) return;
        for (GymWorkHourResponse wh : workHours) {
            Set<GymWorkHourPeriod> whPeriods = az.fitnest.catalog.mapper.GymMapper.expandPeriods(wh.period());
            for (GymWorkHourPeriod p : whPeriods) {
                if (restDays.contains(p)) {
                    throw new BadRequestException("WORK_HOURS_ON_REST_DAY", "error.work_hours_on_rest_day");
                }
            }
        }
    }

    public void createGymStep4(Long gymId, GymCreateStep3Request request) {
        GeocodingResponse geocoding = reverseGeocodingService.reverseGeocode(request.latitude(), request.longitude());
        createGymStep4Internal(gymId, request, geocoding);
    }

    @Transactional
    protected void createGymStep4Internal(Long gymId, GymCreateStep3Request request, GeocodingResponse geocoding) {
        Gym gym = gymRepository.findById(gymId).orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        validateStep(gym, 3);

        Address address = new Address();
        address.setLatitude(request.latitude());
        address.setLongitude(request.longitude());
        address.setAltitude(request.altitude());
        if (geocoding != null) {
            address.setAddressText(geocoding.addressText());
            address.setCity(geocoding.city());
        }
        gym.setAddress(address);

        if (address != null) {
            translationService.autoTranslateAndSave("GYM", gym.getId().toString(), "addressText", address.getAddressText());
            translationService.autoTranslateAndSave("GYM", gym.getId().toString(), "city", address.getCity());
        }

        updateStep(gym, 3);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = "gym-detail", key = "#gymId"),
            @CacheEvict(cacheNames = "gym-images", key = "#gymId")
    })
    public void createGymStep5(Long gymId, MultipartFile coverPhoto, List<String> roomNames, List<MultipartFile> roomPhotos) {
        CompletableFuture<String> coverFuture = null;
        if (coverPhoto != null && !coverPhoto.isEmpty()) {
            MultipartFile validatedCover = fileStorageService.validateAndWrapImage(coverPhoto);
            coverFuture = CompletableFuture.supplyAsync(() ->
                            fileStorageService.saveFile(validatedCover, "/gyms/covers"),
                    imageUploadExecutor
            );
        }

        List<CompletableFuture<RoomImageUploadResult>> roomFutures = new ArrayList<>();
        if (roomNames != null && roomPhotos != null && roomNames.size() == roomPhotos.size() && !roomNames.isEmpty()) {
            for (int i = 0; i < roomPhotos.size(); i++) {
                MultipartFile originalFile = roomPhotos.get(i);
                if (originalFile == null || originalFile.isEmpty()) continue;

                String roomName = roomNames.get(i);
                MultipartFile validatedFile = fileStorageService.validateAndWrapImage(originalFile);

                roomFutures.add(CompletableFuture.supplyAsync(() -> {
                    String url = fileStorageService.saveFile(validatedFile, "/gyms/rooms");
                    return new RoomImageUploadResult(roomName, url);
                }, imageUploadExecutor));
            }
        }

        // Wait for all uploads to complete
        String coverUrl = null;
        if (coverFuture != null) {
            coverUrl = coverFuture.join();
        }

        List<RoomImageUploadResult> roomResults = roomFutures.stream()
                .map(CompletableFuture::join)
                .toList();

        // Save to DB in a single transaction template/call
        final String finalCoverUrl = coverUrl;
        new TransactionTemplate(transactionManager).execute(status -> {
            if (finalCoverUrl != null) {
                updateCoverImageInternal(gymId, finalCoverUrl);
            }
            if (!roomResults.isEmpty()) {
                applyRoomImagesUpdateInternal(gymId, roomResults);
            }
            completeStep5Internal(gymId);
            return null;
        });
    }

    @Transactional
    protected void completeStep5Internal(Long gymId) {
        Gym gym = gymRepository.findById(gymId).orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        validateStep(gym, 4);
        updateStep(gym, 4);
    }

    @Override
    @Transactional
    public void createGymStep6(Long gymId, GymCreateStep6Request request, List<MultipartFile> serviceIcons) {

        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));

        validateStep(gym, 5);

        // İkonları yüklə və URL-ləri map et
        Map<Integer, String> iconUrlByIndex = uploadServiceIcons(serviceIcons);

        updateGymSubscriptionsInternal(gym, request, iconUrlByIndex);
        updateStep(gym, 5);
    }

    @Transactional
    @CacheEvict(cacheNames = "gym-detail", allEntries = true)
    public void updateGymSubscriptions(Long gymId, GymCreateStep6Request request) {
        Gym gym = gymRepository.findById(gymId).orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        updateGymSubscriptionsInternal(gym, request);
    }

    private Map<Integer, String> uploadServiceIcons(List<MultipartFile> serviceIcons) {
        if (serviceIcons == null || serviceIcons.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Integer, String> iconUrlByIndex = new HashMap<>();
        for (int i = 0; i < serviceIcons.size(); i++) {
            MultipartFile icon = serviceIcons.get(i);
            if (icon == null || icon.isEmpty()) continue;
            try {
                MultipartFile validated = fileStorageService.validateAndWrapImage(icon);
                String url = fileStorageService.saveFile(validated, "/gyms/service-icons");
                iconUrlByIndex.put(i, url);
            } catch (Exception ex) {
            }
        }
        return iconUrlByIndex;
    }

    private void updateGymSubscriptionsInternal(
            Gym gym,
            GymCreateStep6Request request,
            Map<Integer, String> iconUrlByIndex) {

        Long gymId = gym.getId();
        gym.getSubscriptions().clear();
        gymRepository.saveAndFlush(gym);

        Set<Long> processedPackages = new HashSet<>();
        Map<String, SupportedService> nameToService = new HashMap<>();
        int globalIconIndex = 0;

        for (GymCreateStep6SubscriptionRequest subReq : request.subscriptions()) {
            if (!processedPackages.add(subReq.packageId())) continue;

            GymSubscription subscription = new GymSubscription();
            subscription.setGym(gym);
            subscription.setPackageId(subReq.packageId());
            subscription.setDailyPrice(subReq.dailyPrice());

            Set<SupportedService> services = new HashSet<>();

            // Mövcud xidmətlər — icon dəyişmir
            if (subReq.supportedServicesId() != null && !subReq.supportedServicesId().isEmpty()) {
                List<SupportedService> existingServices = supportedServiceRepository
                        .findAllById(subReq.supportedServicesId())
                        .stream()
                        .filter(s -> s.getGymId() == null || s.getGymId().equals(gymId))
                        .toList();
                services.addAll(existingServices);
            }

            // Custom xidmətlər — icon varsa set et
            if (subReq.customServices() != null && !subReq.customServices().isEmpty()) {
                for (String customServiceName : subReq.customServices()) {
                    if (customServiceName == null || customServiceName.trim().isEmpty()) {
                        globalIconIndex++;
                        continue;
                    }

                    String trimmedName = customServiceName.trim();
                    String iconUrl = iconUrlByIndex.get(globalIconIndex);
                    globalIconIndex++;

                    SupportedService service = nameToService.get(trimmedName.toLowerCase());
                    if (service == null) {
                        Optional<SupportedService> existingOpt = supportedServiceRepository
                                .findByNameIgnoreCaseAndGymId(trimmedName, gymId);

                        if (existingOpt.isPresent()) {
                            service = existingOpt.get();
                            if (iconUrl != null) {
                                if (service.getIconUrl() != null) {
                                    fileStorageService.deleteFilesAfterCommit(
                                            List.of(service.getIconUrl())
                                    );
                                }
                                service.setIconUrl(iconUrl);
                                service = supportedServiceRepository.save(service);
                            }
                        } else {
                            service = new SupportedService();
                            service.setName(trimmedName);
                            service.setGymId(gymId);
                            if (iconUrl != null) {
                                service.setIconUrl(iconUrl);
                            }
                            service = supportedServiceRepository.save(service);
                            translationService.autoTranslateAndSave(
                                    "SupportedService",
                                    service.getId().toString(),
                                    "name",
                                    service.getName()
                            );
                        }
                        nameToService.put(trimmedName.toLowerCase(), service);
                    }
                    services.add(service);
                }
            }

            subscription.setSupportedServices(services);
            gym.getSubscriptions().add(subscription);
        }

        gymRepository.save(gym);
    }

    private void updateGymSubscriptionsInternal(Gym gym, GymCreateStep6Request request) {
        updateGymSubscriptionsInternal(gym, request, Collections.emptyMap());
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = {"main-page-gyms", "gym-listings", "admin-gyms", "gym-count-by-category", "gym-count-by-subscription", "landing-gyms", "landing-home-gyms", "landing-gym-detail", "landing-stats", "landing-media-public"}, allEntries = true)
    })
    public void createGymStep7(Long gymId, GymCreateStep7Request request) {
        Gym gym = gymRepository.findById(gymId).orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));

        List<CompletableFuture<GymAdminCreateResult>> futures = request.admins().stream()
                .map(adminReq -> CompletableFuture.supplyAsync(() -> {
                    Long userId = identityServiceGrpcClient.createGymAdmin(
                            adminReq.name(),
                            adminReq.surname(),
                            PhoneUtil.normalize(adminReq.phoneNumber()),
                            adminReq.email(),
                            adminReq.password()
                    );
                    return new GymAdminCreateResult(adminReq, userId);
                }, imageUploadExecutor))
                .toList();

        List<GymAdminCreateResult> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        new TransactionTemplate(transactionManager).execute(status -> {
            Gym freshGym = gymRepository.findById(gymId).orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
            for (GymAdminCreateResult res : results) {
                String role = (res.req().role() != null && !res.req().role().trim().isEmpty()) ? res.req().role() : "Super admin";
                az.fitnest.catalog.model.entity.GymAdmin saved = gymAdminRepository.save(
                        az.fitnest.catalog.mapper.GymMapper.toAdminEntity(freshGym, res.req(), res.userId(), role)
                );

                translationService.autoTranslateAndSave("GymAdmin", saved.getId().toString(), "name", saved.getName());
                translationService.autoTranslateAndSave("GymAdmin", saved.getId().toString(), "surname", saved.getSurname());
            }
            finalizeGymStep7Internal(gymId);
            return null;
        });
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = {"main-page-gyms", "gym-listings", "admin-gyms", "gym-count-by-category", "gym-count-by-subscription", "landing-gyms", "landing-home-gyms", "landing-gym-detail", "landing-stats", "landing-media-public"}, allEntries = true)
    })
    public Long createGymComplete(GymCreateCompleteRequest request, MultipartFile coverPhoto,
                                  List<MultipartFile> trainerPhotos, List<MultipartFile> roomPhotos,
                                  List<MultipartFile> serviceIcons) {
        // ═══════════════════════════════════════════════════════════════
        // Phase 1: Validate all data upfront
        // ═══════════════════════════════════════════════════════════════

        // Validate step 1
        GymCreateStep1Request step1 = GymCreateStep1Request.builder()
                .categoryId(request.categoryId())
                .name(request.name())
                .phone(request.phone())
                .description(request.description())
                .email(request.email())
                .lessonTypeIds(request.lessonTypeIds())
                .build();
        validateStep1(step1);

        // Validate step 2 (trainer emails/phones)
        if (request.trainers() != null && !request.trainers().isEmpty()) {
            List<String> emails = request.trainers().stream().map(GymCreateCompleteRequest.TrainerCreateData::email).filter(e -> e != null && !e.isBlank()).toList();
            List<String> phones = request.trainers().stream().map(GymCreateCompleteRequest.TrainerCreateData::phone).filter(p -> p != null && !p.isBlank()).toList();
            validateStep2(emails, phones);
        }

        // Validate step 3 (working hours)
        GymCreateStep2Request step3 = GymCreateStep2Request.builder()
                .generalWorkHours(request.generalWorkHours())
                .workHoursWoman(request.workHoursWoman())
                .workHoursMan(request.workHoursMan())
                .restDays(request.restDays())
                .build();
        validateStep3(step3);

        // Validate step 4 (coordinates)
        GymCreateStep3Request step4 = GymCreateStep3Request.builder()
                .latitude(request.latitude())
                .longitude(request.longitude())
                .build();
        validateStep4(step4);

        // Validate step 5 (images)
        validateStep5(coverPhoto, request.roomNames(), roomPhotos);

        // Validate step 6 (subscriptions)
        GymCreateStep6Request step6 = GymCreateStep6Request.builder()
                .subscriptions(request.subscriptions())
                .build();
        validateStep6(step6, serviceIcons);

        // Validate step 7 (admins)
        GymCreateStep7Request step7 = GymCreateStep7Request.builder()
                .admins(request.admins())
                .build();
        validateStep7(step7);

        // ═══════════════════════════════════════════════════════════════
        // Phase 2: Upload all files in parallel (outside transaction)
        // ═══════════════════════════════════════════════════════════════

        // Geocoding (external call)
        GeocodingResponse geocoding = reverseGeocodingService.reverseGeocode(request.latitude(), request.longitude());

        // Cover photo upload
        CompletableFuture<String> coverFuture = null;
        if (coverPhoto != null && !coverPhoto.isEmpty()) {
            MultipartFile validatedCover = fileStorageService.validateAndWrapImage(coverPhoto);
            coverFuture = CompletableFuture.supplyAsync(() ->
                    fileStorageService.saveFile(validatedCover, "/gyms/covers"), imageUploadExecutor);
        }

        // Trainer photo uploads
        List<CompletableFuture<String>> trainerPhotoFutures = new ArrayList<>();
        if (trainerPhotos != null) {
            for (MultipartFile photo : trainerPhotos) {
                if (photo != null && !photo.isEmpty()) {
                    MultipartFile validated = fileStorageService.validateAndWrapImage(photo);
                    trainerPhotoFutures.add(CompletableFuture.supplyAsync(() ->
                            fileStorageService.saveFile(validated, "/gyms/trainers"), imageUploadExecutor));
                } else {
                    trainerPhotoFutures.add(CompletableFuture.completedFuture(null));
                }
            }
        }

        // Room photo uploads
        List<CompletableFuture<RoomImageUploadResult>> roomFutures = new ArrayList<>();
        if (request.roomNames() != null && roomPhotos != null && request.roomNames().size() == roomPhotos.size()) {
            for (int i = 0; i < roomPhotos.size(); i++) {
                MultipartFile file = roomPhotos.get(i);
                if (file == null || file.isEmpty()) continue;
                String roomName = request.roomNames().get(i);
                MultipartFile validated = fileStorageService.validateAndWrapImage(file);
                roomFutures.add(CompletableFuture.supplyAsync(() -> {
                    String url = fileStorageService.saveFile(validated, "/gyms/rooms");
                    return new RoomImageUploadResult(roomName, url);
                }, imageUploadExecutor));
            }
        }

        // Service icon uploads
        CompletableFuture<Map<Integer, String>> serviceIconsFuture = CompletableFuture.supplyAsync(() ->
                uploadServiceIcons(serviceIcons), imageUploadExecutor);

        // Create gym admin users in identity service (external gRPC call)
        List<CompletableFuture<GymAdminCreateResult>> adminFutures = request.admins().stream()
                .map(adminReq -> CompletableFuture.supplyAsync(() -> {
                    Long userId = identityServiceGrpcClient.createGymAdmin(
                            adminReq.name(), adminReq.surname(),
                            PhoneUtil.normalize(adminReq.phoneNumber()),
                            adminReq.email(), adminReq.password()
                    );
                    return new GymAdminCreateResult(adminReq, userId);
                }, imageUploadExecutor))
                .toList();

        // Wait for all parallel operations to complete
        String finalCoverUrl = coverFuture != null ? coverFuture.join() : null;
        List<String> trainerPhotoUrls = trainerPhotoFutures.stream().map(CompletableFuture::join).toList();
        List<RoomImageUploadResult> roomResults = roomFutures.stream().map(CompletableFuture::join).toList();
        List<GymAdminCreateResult> adminResults = adminFutures.stream().map(CompletableFuture::join).toList();
        Map<Integer, String> iconUrlByIndex = serviceIconsFuture.join();

        // ═══════════════════════════════════════════════════════════════
        // Phase 3: Save everything in a single transaction
        // ═══════════════════════════════════════════════════════════════

        Long gymId = new TransactionTemplate(transactionManager).execute(status -> {
            // Step 1: Create Gym entity
            Category category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("CATEGORY_NOT_FOUND", "error.category_not_found"));
            Gym gym = new Gym();
            gym.setName(request.name());
            gym.setDescription(request.description());
            gym.setPhone(PhoneUtil.normalize(request.phone()));
            gym.setEmail(request.email() != null && request.email().isBlank() ? null : request.email());
            gym.setCategory(category);

            // Step 3: Working Hours
            updateWorkHours(gym.getGeneralWorkHours(), request.generalWorkHours());
            updateWorkHours(gym.getWorkHoursWoman(), request.workHoursWoman());
            updateWorkHours(gym.getWorkHoursMan(), request.workHoursMan());

            if (request.restDays() != null) {
                Set<GymWorkHourPeriod> restDays = request.restDays().stream()
                        .flatMap(r -> az.fitnest.catalog.mapper.GymMapper.expandPeriods(r.period()).stream())
                        .collect(Collectors.toSet());
                gym.getRestDays().addAll(restDays);
            }

            // Step 4: Address
            Address address = new Address();
            address.setLatitude(request.latitude());
            address.setLongitude(request.longitude());
            address.setAltitude(request.altitude());
            if (geocoding != null) {
                address.setAddressText(geocoding.addressText());
                address.setCity(geocoding.city());
            }
            gym.setAddress(address);

            // Step 5: Cover image
            if (finalCoverUrl != null) {
                gym.setCoverImageUrl(finalCoverUrl);
            }

            // Set status to ACTIVE directly (skipping DRAFT)
            gym.setStatus(GymStatus.ACTIVE);
            gym.setCreationStep(7);

            Gym savedGym = gymRepository.save(gym);
            Long savedGymId = savedGym.getId();

            // Translations for gym
            translationService.autoTranslateAndSave("GYM", savedGymId.toString(), "name", request.name());
            translationService.autoTranslateAndSave("GYM", savedGymId.toString(), "description", request.description());
            if (savedGym.getAddress() != null) {
                translationService.autoTranslateAndSave("GYM", savedGymId.toString(), "addressText", savedGym.getAddress().getAddressText());
                translationService.autoTranslateAndSave("GYM", savedGymId.toString(), "city", savedGym.getAddress().getCity());
            }

            // Step 2: Trainers
            if (request.trainers() != null && !request.trainers().isEmpty()) {
                List<String> names = request.trainers().stream().map(GymCreateCompleteRequest.TrainerCreateData::name).toList();
                List<String> surnames = request.trainers().stream().map(GymCreateCompleteRequest.TrainerCreateData::surname).toList();
                List<Long> professionIds = request.trainers().stream().map(GymCreateCompleteRequest.TrainerCreateData::professionId).toList();
                List<String> emails = request.trainers().stream().map(t -> t.email() != null ? t.email() : "").toList();
                List<String> phones = request.trainers().stream().map(t -> t.phone() != null ? t.phone() : "").toList();
                List<String> lessonTypesPerTrainer = request.trainers().stream().map(t -> t.lessonTypeIds() != null ? t.lessonTypeIds() : "").toList();

                // Convert trainer photo URLs to a list that matches trainer order
                // trainerPhotoUrls may have fewer entries if some trainers have no photo
                gymTrainerService.addTrainersWithUrls(savedGymId, names, surnames, professionIds, emails, phones, trainerPhotoUrls, lessonTypesPerTrainer);
            }

            // Step 5: Room images
            if (!roomResults.isEmpty()) {
                for (RoomImageUploadResult res : roomResults) {
                    Room room = Room.builder()
                            .name(res.roomName())
                            .gym(savedGym)
                            .build();
                    savedGym.getRooms().add(room);

                    RoomImage roomImage = RoomImage.builder()
                            .room(room)
                            .pictureUrl(res.url())
                            .build();
                    room.getImages().add(roomImage);
                }
                gymRepository.save(savedGym);
            }

            // Step 6: Subscriptions
            updateGymSubscriptionsInternal(savedGym, step6, iconUrlByIndex);

            // Step 7: Admins
            for (GymAdminCreateResult res : adminResults) {
                String role = (res.req().role() != null && !res.req().role().trim().isEmpty()) ? res.req().role() : "Super admin";
                az.fitnest.catalog.model.entity.GymAdmin saved = gymAdminRepository.save(
                        az.fitnest.catalog.mapper.GymMapper.toAdminEntity(savedGym, res.req(), res.userId(), role)
                );
                translationService.autoTranslateAndSave("GymAdmin", saved.getId().toString(), "name", saved.getName());
                translationService.autoTranslateAndSave("GymAdmin", saved.getId().toString(), "surname", saved.getSurname());
            }

            return savedGymId;
        });

        // Phase 4: Post-commit actions
        gymQrCodeService.generateAndSaveQrCode(gymId);
        gymActivationNotifier.notifyNewActiveGym(gymId, request.name());

        return gymId;
    }

    @Transactional
    public void addGymAdmin(Long gymId, GymAdminCreateRequest request) {
        if (gymAdminRepository.existsByGymIdAndPhoneNumber(gymId, PhoneUtil.normalize(request.phoneNumber()))) {
            throw new BadRequestException("ADMIN_ALREADY_IN_GYM", "error.admin_already_in_gym");
        }
        Gym gym = gymRepository.findById(gymId).orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        Long userId = identityServiceGrpcClient.createGymAdmin(request.name(), request.surname(), PhoneUtil.normalize(request.phoneNumber()), request.email(), request.password());
        String role = (request.role() != null && !request.role().trim().isEmpty()) ? request.role() : "Admin";
        az.fitnest.catalog.model.entity.GymAdmin saved = gymAdminRepository.save(az.fitnest.catalog.mapper.GymMapper.toAdminEntity(gym, request, userId, role));

        translationService.autoTranslateAndSave("GymAdmin", saved.getId().toString(), "name", saved.getName());
        translationService.autoTranslateAndSave("GymAdmin", saved.getId().toString(), "surname", saved.getSurname());
    }

    @Transactional
    public void updateGymAdmin(Long gymId, Long adminId, GymAdminUpdateRequest request) {
        az.fitnest.catalog.model.entity.GymAdmin admin = gymAdminRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("ADMIN_NOT_FOUND", "error.admin_not_found"));

        if (!admin.getGym().getId().equals(gymId)) {
            throw new BadRequestException("ADMIN_GYM_MISMATCH", "error.admin_gym_mismatch");
        }

        admin.setName(request.name());
        admin.setSurname(request.surname());
        admin.setPhoneNumber(PhoneUtil.normalize(request.phoneNumber()));
        admin.setEmail(request.email());
        if (request.role() != null && !request.role().trim().isEmpty()) {
            admin.setRole(request.role());
        }

        gymAdminRepository.save(admin);

        if (admin.getUserId() != null) {
            identityServiceGrpcClient.updateUserProfile(
                    admin.getUserId(),
                    request.name(),
                    request.surname(),
                    request.email(),
                    PhoneUtil.normalize(request.phoneNumber())
            );
        }

        translationService.autoTranslateAndSave("GymAdmin", admin.getId().toString(), "name", admin.getName());
        translationService.autoTranslateAndSave("GymAdmin", admin.getId().toString(), "surname", admin.getSurname());
    }

    @Transactional
    public void deleteGymAdmin(Long gymId, Long adminId) {
        az.fitnest.catalog.model.entity.GymAdmin admin = gymAdminRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("ADMIN_NOT_FOUND", "error.admin_not_found"));

        if (!admin.getGym().getId().equals(gymId)) {
            throw new BadRequestException("ADMIN_GYM_MISMATCH", "error.admin_gym_mismatch");
        }

        if (admin.getUserId() != null) {
            try {
                identityServiceGrpcClient.deactivateUser(admin.getUserId(), "Gym admin deleted");
            } catch (Exception e) {
            }
        }

        gymAdminRepository.delete(admin);
    }

    @Transactional
    protected void finalizeGymStep7Internal(Long gymId) {
        Gym gym = gymRepository.findById(gymId).orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        validateStep(gym, 6);
        gym.setStatus(GymStatus.ACTIVE);
        gym.setCreationStep(7);
        gymRepository.save(gym);
        gymQrCodeService.generateAndSaveQrCode(gym.getId());
        gymActivationNotifier.notifyNewActiveGym(gym.getId(), gym.getName());
    }

    private void validateStep(Gym gym, int requiredStep) {
        if (gym.getStatus() == GymStatus.ACTIVE ||
                gym.getStatus() == GymStatus.INACTIVE) {
            throw new BadRequestException("GYM_NOT_EDITABLE", "error.gym_not_editable_via_steps");
        }
        Integer currentStep = gym.getCreationStep() != null ? gym.getCreationStep() : 1;

        if (requiredStep == 2 && currentStep == 1) {
            return;
        }

        if (currentStep < requiredStep) {
            throw new BadRequestException("INVALID_STEP", "error.invalid_step");
        }
    }

    private void updateStep(Gym gym, int completedStep) {
        Integer currentStep = gym.getCreationStep() != null ? gym.getCreationStep() : 1;
        if (currentStep <= completedStep) {
            gym.setCreationStep(completedStep + 1);
            gymRepository.save(gym);
        }
    }

    @Transactional
    public SupportedServiceResponse createSupportedService(SupportedServiceRequest request, MultipartFile icon) {
        if (supportedServiceRepository.findByNameIgnoreCaseAndGymId(request.name(), request.gymId()).isPresent()) {
            throw new BadRequestException("SERVICE_ALREADY_EXISTS", "error.service_already_exists");
        }
        SupportedService service = new SupportedService();
        service.setName(request.name());
        service.setGymId(request.gymId());

        if (icon != null && !icon.isEmpty()) {
            MultipartFile validated = fileStorageService.validateAndWrapImage(icon);
            String iconUrl = fileStorageService.saveFile(validated, "/gyms/service-icons");
            service.setIconUrl(iconUrl);
        }

        service = supportedServiceRepository.save(service);

        translationService.autoTranslateAndSave("SupportedService", service.getId().toString(), "name", service.getName());

        return new SupportedServiceResponse(service.getId(), service.getName(), service.getGymId(), service.getIconUrl());
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(cacheNames = {"gymDetails", "admin-gyms"}, allEntries = true)
    public SupportedServiceResponse updateSupportedService(Long id, SupportedServiceRequest request, MultipartFile icon) {
        SupportedService service = supportedServiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("SERVICE_NOT_FOUND", "error.service_not_found"));

        if (request != null && request.name() != null && !request.name().isBlank()) {
            String trimmedName = request.name().trim();
            supportedServiceRepository.findByNameIgnoreCaseAndGymId(trimmedName, service.getGymId())
                    .filter(existing -> !existing.getId().equals(id))
                    .ifPresent(existing -> {
                        throw new BadRequestException("SERVICE_ALREADY_EXISTS", "error.service_already_exists");
                    });
            if (!trimmedName.equals(service.getName())) {
                service.setName(trimmedName);
                translationService.autoTranslateAndSave("SupportedService", service.getId().toString(), "name", trimmedName);
            }
        }

        if (icon != null && !icon.isEmpty()) {
            MultipartFile validated = fileStorageService.validateAndWrapImage(icon);
            String iconUrl = fileStorageService.saveFile(validated, "/gyms/service-icons");
            String previousIconUrl = service.getIconUrl();
            service.setIconUrl(iconUrl);
            if (previousIconUrl != null && !previousIconUrl.isBlank()) {
                fileStorageService.deleteFilesAfterCommit(List.of(previousIconUrl));
            }
        }

        service = supportedServiceRepository.save(service);
        return new SupportedServiceResponse(service.getId(), service.getName(), service.getGymId(), service.getIconUrl());
    }

    @Transactional
    @org.springframework.cache.annotation.CacheEvict(cacheNames = {"gymDetails", "admin-gyms"}, allEntries = true)
    public void deleteSupportedService(Long id) {
        supportedServiceRepository.deleteSubscriptionAssociations(id);
        supportedServiceRepository.deleteById(id);
    }

    @Override
    public GeocodingResponse reverseGeocode(Double lat, Double lng) {
        return reverseGeocodingService.reverseGeocode(lat, lng);
    }

    @Override
    public java.util.List<GeocodingResponse> forwardGeocode(String query) {
        return reverseGeocodingService.forwardGeocode(query);
    }

    @Transactional
    @CacheEvict(cacheNames = {"admin-gyms", "main-page-gyms", "gym-listings", "gym-count-by-category", "gym-count-by-subscription", "landing-gyms", "landing-home-gyms", "landing-gym-detail", "landing-stats", "landing-media-public"}, allEntries = true)
    public void toggleGymStatus(Long gymId, boolean enabled) {
        Gym gym = gymRepository.findById(gymId).orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        gym.setStatus(enabled ? GymStatus.ACTIVE : GymStatus.INACTIVE);
        gymRepository.save(gym);
    }

    private void updateWorkHours(Set<GymWorkHour> target, Set<GymWorkHourResponse> source) {
        target.clear();
        if (source != null) {
            target.addAll(az.fitnest.catalog.mapper.GymMapper.toWorkHours(source));
        }
    }

    @Override
    @Transactional
    @org.springframework.cache.annotation.CacheEvict(cacheNames = {"gymDetails", "admin-gyms", "nearestGyms", "gym-listings", "gym-count-by-category", "gym-count-by-subscription"}, allEntries = true)
    public void updateGymInfo(Long gymId, az.fitnest.catalog.dto.request.GymInfoUpdateRequest request) {
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));

        if (request.categoryId() != null) {
            Category category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("CATEGORY_NOT_FOUND", "error.category_not_found"));
            gym.setCategory(category);
        }

        if (request.name() != null) gym.setName(request.name());
        if (request.description() != null) gym.setDescription(request.description());
        if (request.phone() != null) gym.setPhone(request.phone());
        if (request.email() != null) gym.setEmail(request.email().isBlank() ? null : request.email());

        if (gym.getAddress() == null) {
            gym.setAddress(new Address());
        }

        if (request.city() != null) gym.getAddress().setCity(request.city());
        if (request.address() != null) gym.getAddress().setAddressText(request.address());
        if (request.latitude() != null) gym.getAddress().setLatitude(request.latitude());
        if (request.longitude() != null) gym.getAddress().setLongitude(request.longitude());
        if (request.altitude() != null) gym.getAddress().setAltitude(request.altitude());

        gymRepository.save(gym);

        if (request.name() != null) {
            translationService.autoTranslateAndSave("GYM", gym.getId().toString(), "name", request.name());
        }
        if (request.description() != null) {
            translationService.autoTranslateAndSave("GYM", gym.getId().toString(), "description", request.description());
        }
        if (gym.getAddress() != null) {
            if (request.address() != null) {
                translationService.autoTranslateAndSave("GYM", gym.getId().toString(), "addressText", gym.getAddress().getAddressText());
            }
            if (request.city() != null) {
                translationService.autoTranslateAndSave("GYM", gym.getId().toString(), "city", gym.getAddress().getCity());
            }
        }
    }

    @Override
    @Transactional
    public void updateReservationStatusAdmin(Long reservationId, az.fitnest.catalog.model.enums.ReservationStatus status, String reason) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("RESERVATION_NOT_FOUND", "error.reservation_not_found"));

        verifyGymOwnership(reservation.getGym().getId());

        az.fitnest.catalog.model.enums.ReservationStatus oldStatus = reservation.getStatus();
        reservation.setStatus(status);
        if (status == az.fitnest.catalog.model.enums.ReservationStatus.CANCELLED || status == az.fitnest.catalog.model.enums.ReservationStatus.REJECTED) {
            reservation.setCancelReasonText(reason);
            reservation.setCancelledAt(LocalDateTime.now());
        } else if (status == az.fitnest.catalog.model.enums.ReservationStatus.APPROVED) {
            reservation.setApprovedAt(LocalDateTime.now());
        }

        reservation = reservationRepository.save(reservation);

        if (!Boolean.TRUE.equals(reservation.getAttended()) &&
            (oldStatus == az.fitnest.catalog.model.enums.ReservationStatus.APPROVED || oldStatus == az.fitnest.catalog.model.enums.ReservationStatus.PENDING) &&
            (status == az.fitnest.catalog.model.enums.ReservationStatus.CANCELLED || status == az.fitnest.catalog.model.enums.ReservationStatus.REJECTED)) {
            try {
                orderServiceGrpcClient.restoreSession(reservation.getUserId());
            } catch (Exception e) {
            }
        }

        if (status == az.fitnest.catalog.model.enums.ReservationStatus.REJECTED || status == az.fitnest.catalog.model.enums.ReservationStatus.APPROVED) {
            sendStatusUpdateNotificationToUser(reservation, status, reason);
        }
    }

    @Override
    @Transactional
    public void addLessonHourAdmin(Long gymId, az.fitnest.catalog.dto.request.LessonHourRequest request) {
        verifyGymOwnership(gymId);
        if (request.lessonTypeId() == null) {
            throw new BadRequestException("LESSON_TYPE_REQUIRED", "error.lesson_type_required");
        }
        Trainer trainer = null;
        if (request.trainerId() != null) {
            trainer = trainerRepository.findById(request.trainerId())
                    .orElseThrow(() -> new ResourceNotFoundException("TRAINER_NOT_FOUND", "error.trainer_not_found"));
        }

        az.fitnest.catalog.model.entity.LessonType lessonType = null;
        if (request.lessonTypeId() != null) {
            lessonType = lessonTypeRepository.findById(request.lessonTypeId())
                    .orElseThrow(() -> new ResourceNotFoundException("LESSON_TYPE_NOT_FOUND", "error.lesson_type_not_found"));
        }

        TrainerReservationDate trd = TrainerReservationDate.builder()
                .gymId(gymId)
                .trainer(trainer)
                .classType(lessonType)
                .date(request.date())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .emptySpaces(request.maxSlots())
                .status(az.fitnest.catalog.model.enums.SessionStatus.OPEN)
                .build();

        trainerReservationDateRepository.save(trd);

        gymRepository.findById(gymId).ifPresent(gym -> {
            if (!Boolean.TRUE.equals(gym.getIsReservationEnabled())) {
                gym.setIsReservationEnabled(true);
                gymRepository.save(gym);
            }
        });
    }

    @Override
    @Transactional
    public void deleteLessonHourAdmin(Long lessonHourId) {
        trainerReservationDateRepository.findById(lessonHourId).ifPresent(session -> {
            Long gymId = session.getGymId();
            if (gymId != null) {
                verifyGymOwnership(gymId);
            }

            List<az.fitnest.catalog.model.entity.Reservation> reservations = reservationRepository.findByReservationDateId(lessonHourId);
            List<az.fitnest.catalog.model.entity.Reservation> activeReservations = reservations.stream()
                    .filter(r -> r.getStatus() == az.fitnest.catalog.model.enums.ReservationStatus.APPROVED
                            || r.getStatus() == az.fitnest.catalog.model.enums.ReservationStatus.PENDING)
                    .collect(Collectors.toList());

            if (!activeReservations.isEmpty()) {
                for (az.fitnest.catalog.model.entity.Reservation r : activeReservations) {
                    r.setStatus(az.fitnest.catalog.model.enums.ReservationStatus.CANCELLED);
                    r.setCancelledAt(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Baku")));
                    reservationRepository.save(r);
                    sendLessonHourCancellationNotification(r, session);
                }
                session.setStatus(az.fitnest.catalog.model.enums.SessionStatus.CANCELLED);
                trainerReservationDateRepository.save(session);
            } else {
                if (!reservations.isEmpty()) {
                    session.setStatus(az.fitnest.catalog.model.enums.SessionStatus.CANCELLED);
                    trainerReservationDateRepository.save(session);
                } else {
                    trainerReservationDateRepository.deleteById(lessonHourId);
                }
            }

            boolean hasActiveSessions = gymId != null && trainerReservationDateRepository.findByGymId(gymId).stream()
                    .anyMatch(s -> s.getStatus() != az.fitnest.catalog.model.enums.SessionStatus.CANCELLED);

            if (gymId != null && !hasActiveSessions) {
                gymRepository.findById(gymId).ifPresent(gym -> {
                    if (Boolean.TRUE.equals(gym.getIsReservationEnabled())) {
                        gym.setIsReservationEnabled(false);
                        gymRepository.save(gym);
                    }
                });
            }
        });
    }

    private void sendLessonHourCancellationNotification(az.fitnest.catalog.model.entity.Reservation r, az.fitnest.catalog.model.entity.TrainerReservationDate session) {
        if (r == null || r.getUserId() == null) {
            return;
        }
        final Long userId = r.getUserId();
        final String gymName = r.getGym() != null ? r.getGym().getName() : "";
        
        String userLang = "AZ";
        try {
            az.fitnest.catalog.client.CachedUser user = userServiceGrpcClient.getUserById(userId);
            if (user != null && user.getLanguage() != null && !user.getLanguage().isEmpty()) {
                userLang = user.getLanguage().toUpperCase();
            }
        } catch (Exception e) {
            // Ignored
        }

        java.time.format.DateTimeFormatter dateFormatter = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
        java.time.format.DateTimeFormatter timeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
        
        String dateStr = session.getDate() != null ? session.getDate().format(dateFormatter) : "";
        String startTimeStr = session.getStartTime() != null ? session.getStartTime().format(timeFormatter) : "";
        String endTimeStr = session.getEndTime() != null ? session.getEndTime().format(timeFormatter) : "";

        final String title;
        final String body;

        switch (userLang) {
            case "EN":
                title = "Lesson hour cancelled";
                body = String.format("The lesson hour at %s on %s between %s-%s has been cancelled, please sign up for another hour", gymName, dateStr, startTimeStr, endTimeStr);
                break;
            case "RU":
                title = "Занятие отменено";
                body = String.format("Занятие в %s %s с %s по %s отменено, пожалуйста, запишитесь на другое время", gymName, dateStr, startTimeStr, endTimeStr);
                break;
            case "AZ":
            default:
                title = "Dərs saatı ləğv edildi";
                body = String.format("%s -da %s tarixində %s-%s arası dərs saatı ləğv edilmişdir,xahiş edirik digər saatlardan biri üçün məşqə yazılasınız", gymName, dateStr, startTimeStr, endTimeStr);
                break;
        }

        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        java.util.concurrent.CompletableFuture.runAsync(() -> {
                            try {
                                notificationsServiceClient.sendPushNotification(userId, title, body);
                            } catch (Exception e) {
                                // Ignore
                            }
                        });
                    }
                }
            );
        } else {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    notificationsServiceClient.sendPushNotification(userId, title, body);
                } catch (Exception e) {
                    // Ignore
                }
            });
        }
    }

    @Override
    public void validateStep1(GymCreateStep1Request request) {
        if (request.categoryId() == null) {
            throw new BadRequestException("CATEGORY_REQUIRED", "error.category_required");
        }
        categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("CATEGORY_NOT_FOUND", "error.category_not_found"));

        if (request.lessonTypeIds() != null && !request.lessonTypeIds().isEmpty()) {
            List<az.fitnest.catalog.model.entity.LessonType> globalLessonTypes = lessonTypeRepository.findAllById(request.lessonTypeIds());
            if (globalLessonTypes.size() != request.lessonTypeIds().size()) {
                throw new BadRequestException("INVALID_LESSON_TYPES", "error.invalid_lesson_types");
            }
        }
    }

    @Override
    public void validateStep2(List<String> emails, List<String> phones) {
        if (emails != null) {
            for (String email : emails) {
                if (trainerRepository.existsByEmail(email)) {
                    throw new BadRequestException("TRAINER_EMAIL_EXISTS", "error.trainer_email_exists");
                }
            }
        }
        if (phones != null) {
            for (String phone : phones) {
                String normalized = PhoneUtil.normalize(phone);
                if (trainerRepository.existsByPhone(normalized)) {
                    throw new BadRequestException("TRAINER_PHONE_EXISTS", "error.trainer_phone_exists");
                }
            }
        }
    }

    @Override
    public void validateStep3(GymCreateStep2Request request) {
        Set<GymWorkHourPeriod> restDays = new HashSet<>();
        if (request.restDays() != null) {
            restDays = request.restDays().stream()
                    .flatMap(r -> az.fitnest.catalog.mapper.GymMapper.expandPeriods(r.period()).stream())
                    .collect(java.util.stream.Collectors.toSet());
        }

        validateNoWorkHoursOnRestDays(request.generalWorkHours(), restDays, "general");
        validateNoWorkHoursOnRestDays(request.workHoursWoman(), restDays, "woman");
        validateNoWorkHoursOnRestDays(request.workHoursMan(), restDays, "man");
    }

    @Override
    public void validateStep4(GymCreateStep3Request request) {
        if (request.latitude() == null || request.longitude() == null) {
            throw new BadRequestException("COORDINATES_REQUIRED", "error.coordinates_required");
        }
    }

    @Override
    public void validateStep5(MultipartFile coverPhoto, List<String> roomNames, List<MultipartFile> roomPhotos) {
        if (coverPhoto != null && !coverPhoto.isEmpty()) {
            fileStorageService.validateAndWrapImage(coverPhoto);
        }
        if (roomPhotos != null) {
            for (MultipartFile file : roomPhotos) {
                if (file != null && !file.isEmpty()) {
                    fileStorageService.validateAndWrapImage(file);
                }
            }
        }
    }

    @Override
    public void validateStep6(GymCreateStep6Request request, List<MultipartFile> serviceIcons) {

        if (request.subscriptions() == null || request.subscriptions().isEmpty()) {
            throw new BadRequestException("SUBSCRIPTION_REQUIRED", "error.subscription_required");
        }

        for (GymCreateStep6SubscriptionRequest subReq : request.subscriptions()) {
            if (!orderServiceGrpcClient.checkPackageExists(subReq.packageId())) {
                throw new BadRequestException("PACKAGE_NOT_FOUND", "error.package_not_found");
            }
        }

        if (serviceIcons != null && !serviceIcons.isEmpty()) {
            for (MultipartFile icon : serviceIcons) {
                String contentType = icon.getContentType();
                if (contentType == null || !contentType.startsWith("image/")) {
                    throw new BadRequestException(
                            "INVALID_ICON_TYPE",
                            "Xidmət ikonu yalnız şəkil formatında olmalıdır: " + icon.getOriginalFilename()
                    );
                }
            }
        }
    }

    @Override
    public void validateStep7(GymCreateStep7Request request) {
        if (request.admins() == null || request.admins().isEmpty()) {
            throw new BadRequestException("ADMIN_REQUIRED", "error.admin_required");
        }
        java.util.Set<String> phoneNumbers = new java.util.HashSet<>();
        for (GymAdminCreateRequest adminReq : request.admins()) {
            String normalizedPhone = PhoneUtil.normalize(adminReq.phoneNumber());

            if (adminReq.email() != null && !adminReq.email().isBlank()) {
                if (!adminReq.email().matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}$")) {
                    throw new BadRequestException("INVALID_EMAIL_FORMAT", "error.invalid_email_format");
                }
            }

            if (!phoneNumbers.add(normalizedPhone)) {
                throw new BadRequestException("DUPLICATE_ADMIN_PHONE", "error.duplicate_admin_phone");
            }
        }
    }

    private void sendStatusUpdateNotificationToUser(Reservation reservation, az.fitnest.catalog.model.enums.ReservationStatus newStatus, String reason) {
        if (reservation == null || reservation.getUserId() == null) {
            return;
        }
        final Long userId = reservation.getUserId();
        final String gymName = reservation.getGym() != null ? reservation.getGym().getName() : "";
        final String lessonName = reservation.getLessonType() != null ? reservation.getLessonType() :
                (reservation.getCategory() != null ? reservation.getCategory().getName() : "Məşq");
        
        // Resolve user language preference from UserServiceGrpcClient
        String userLang = "AZ";
        try {
            az.fitnest.catalog.client.CachedUser user = userServiceGrpcClient.getUserById(userId);
            if (user != null && user.getLanguage() != null && !user.getLanguage().isEmpty()) {
                userLang = user.getLanguage().toUpperCase();
            }
        } catch (Exception e) {
            // Ignored
        }

        final String title;
        final String body;

        if (newStatus == az.fitnest.catalog.model.enums.ReservationStatus.REJECTED) {
            String targetReason = reason;
            if (reason != null && !reason.trim().isEmpty()) {
                if ("EN".equals(userLang)) {
                    try {
                        String translated = translationService.translateText(reason, "en");
                        if (translated != null && !translated.trim().isEmpty()) {
                            targetReason = translated;
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                } else if ("RU".equals(userLang)) {
                    try {
                        String translated = translationService.translateText(reason, "ru");
                        if (translated != null && !translated.trim().isEmpty()) {
                            targetReason = translated;
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }

            switch (userLang) {
                case "EN":
                    title = "Reservation Not Approved";
                    if (targetReason != null && !targetReason.trim().isEmpty()) {
                        body = String.format("%s - %s reservation was not approved. Reason: %s", gymName, lessonName, targetReason);
                    } else {
                        body = String.format("%s - %s reservation was not approved.", gymName, lessonName);
                    }
                    break;
                case "RU":
                    title = "Бронирование отклонено";
                    if (targetReason != null && !targetReason.trim().isEmpty()) {
                        body = String.format("%s - %s ваше бронирование не одобрено. Причина: %s", gymName, lessonName, targetReason);
                    } else {
                        body = String.format("%s - %s ваше бронирование не одобрено.", gymName, lessonName);
                    }
                    break;
                case "AZ":
                default:
                    title = "Rezervasiya təsdiq edilmədi";
                    if (targetReason != null && !targetReason.trim().isEmpty()) {
                        body = String.format("%s - %s rezervasiyanız təsdiq edilmədi. Səbəb: %s", gymName, lessonName, targetReason);
                    } else {
                        body = String.format("%s - %s rezervasiyanız təsdiq edilmədi.", gymName, lessonName);
                    }
                    break;
            }
        } else if (newStatus == az.fitnest.catalog.model.enums.ReservationStatus.APPROVED) {
            switch (userLang) {
                case "EN":
                    title = "Reservation Confirmed";
                    body = String.format("Your reservation for %s - %s has been successfully confirmed!", gymName, lessonName);
                    break;
                case "RU":
                    title = "Бронирование подтверждено";
                    body = String.format("Ваше бронирование %s - %s успешно подтверждено!", gymName, lessonName);
                    break;
                case "AZ":
                default:
                    title = "Rezervasiya təsdiqləndi";
                    body = String.format("%s - %s rezervasiyanız uğurla təsdiqləndi!", gymName, lessonName);
                    break;
            }
        } else {
            return;
        }

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        CompletableFuture.runAsync(() -> {
                            try {
                                notificationsServiceClient.sendPushNotification(userId, title, body);
                            } catch (Exception e) {
                                // Ignore to avoid breaking execution
                            }
                        });
                    }
                }
            );
        } else {
            CompletableFuture.runAsync(() -> {
                try {
                    notificationsServiceClient.sendPushNotification(userId, title, body);
                } catch (Exception e) {
                    // Ignore
                }
            });
        }
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = "admin-gyms", allEntries = true)
    public GymCreateStep1Response createGymStep1V2(az.fitnest.catalog.dto.request.GymCreateStep1RequestV2 request) {
        if (request.mainCategoryDetails() == null || request.mainCategoryDetails().isEmpty()) {
            throw new BadRequestException("MAIN_CATEGORY_REQUIRED", "error.main_category_required");
        }
        List<Long> mainCategoryIds = request.mainCategoryDetails().stream().map(CategoryDetail::categoryId).toList();
        List<Long> subCategoryIds = request.subCategoryDetails() == null ? new ArrayList<>() : request.subCategoryDetails().stream().map(CategoryDetail::categoryId).toList();

        for (Long mainId : mainCategoryIds) {
            if (subCategoryIds.contains(mainId)) {
                throw new BadRequestException("CATEGORIES_MUST_BE_DIFFERENT", "error.categories_must_be_different");
            }
        }
        List<Category> mainCategories = categoryRepository.findAllById(mainCategoryIds);
        if (mainCategories.size() != mainCategoryIds.size()) {
            throw new ResourceNotFoundException("MAIN_CATEGORY_NOT_FOUND", "error.main_category_not_found");
        }

        List<Category> subCategories = new java.util.ArrayList<>();
        if (!subCategoryIds.isEmpty()) {
            subCategories = categoryRepository.findAllById(subCategoryIds);
            if (subCategories.size() != subCategoryIds.size()) {
                throw new ResourceNotFoundException("SUB_CATEGORY_NOT_FOUND", "error.sub_category_not_found");
            }
        }

        Gym gym = new Gym();
        gym.setName(request.name());
        gym.setDescription(request.description());
        gym.setPhone(PhoneUtil.normalize(request.phone()));
        gym.setEmail(request.email() != null && request.email().isBlank() ? null : request.email());
        gym.setMainCategories(new java.util.HashSet<>(mainCategories));
        gym.setSubCategories(new java.util.HashSet<>(subCategories));
        gym.setHasSubcategories(request.hasSubcategories() != null && request.hasSubcategories());
        gym.setStatus(GymStatus.DRAFT);
        gym.setCreationStep(1);
        gym = gymRepository.save(gym);

        // Save category specific descriptions
        for (CategoryDetail detail : request.mainCategoryDetails()) {
            Category category = mainCategories.stream().filter(c -> c.getId().equals(detail.categoryId())).findFirst().orElse(null);
            if (category != null) {
                GymDescription gymDesc = GymDescription.builder()
                        .gym(gym)
                        .category(category)
                        .phone(detail.phone() != null ? PhoneUtil.normalize(detail.phone()) : null)
                        .description(detail.description())
                        .build();
                gymDescriptionRepository.save(gymDesc);
            }
        }
        if (request.subCategoryDetails() != null) {
            for (CategoryDetail detail : request.subCategoryDetails()) {
                Category category = subCategories.stream().filter(c -> c.getId().equals(detail.categoryId())).findFirst().orElse(null);
                if (category != null) {
                    GymDescription gymDesc = GymDescription.builder()
                            .gym(gym)
                            .category(category)
                            .phone(detail.phone() != null ? PhoneUtil.normalize(detail.phone()) : null)
                            .description(detail.description())
                            .build();
                    gymDescriptionRepository.save(gymDesc);
                }
            }
        }

        translationService.autoTranslateAndSave("GYM", gym.getId().toString(), "name", request.name());
        translationService.autoTranslateAndSave("GYM", gym.getId().toString(), "description", request.description());

        return new GymCreateStep1Response(gym.getId());
    }

    @Override
    public void validateStep1V2(az.fitnest.catalog.dto.request.GymCreateStep1RequestV2 request) {
        if (request.mainCategoryDetails() == null || request.mainCategoryDetails().isEmpty()) {
            throw new BadRequestException("MAIN_CATEGORY_REQUIRED", "error.main_category_required");
        }
        List<Long> mainCategoryIds = request.mainCategoryDetails().stream().map(CategoryDetail::categoryId).toList();
        List<Long> subCategoryIds = request.subCategoryDetails() == null ? new ArrayList<>() : request.subCategoryDetails().stream().map(CategoryDetail::categoryId).toList();

        for (Long mainId : mainCategoryIds) {
            if (subCategoryIds.contains(mainId)) {
                throw new BadRequestException("CATEGORIES_MUST_BE_DIFFERENT", "error.categories_must_be_different");
            }
        }
        List<Category> mainCategories = categoryRepository.findAllById(mainCategoryIds);
        if (mainCategories.size() != mainCategoryIds.size()) {
            throw new ResourceNotFoundException("MAIN_CATEGORY_NOT_FOUND", "error.main_category_not_found");
        }

        if (!subCategoryIds.isEmpty()) {
            List<Category> subCategories = categoryRepository.findAllById(subCategoryIds);
            if (subCategories.size() != subCategoryIds.size()) {
                throw new ResourceNotFoundException("SUB_CATEGORY_NOT_FOUND", "error.sub_category_not_found");
            }
        }

        if (request.lessonTypeIds() != null && !request.lessonTypeIds().isEmpty()) {
            List<az.fitnest.catalog.model.entity.LessonType> globalLessonTypes = lessonTypeRepository.findAllById(request.lessonTypeIds());
            if (globalLessonTypes.size() != request.lessonTypeIds().size()) {
                throw new BadRequestException("INVALID_LESSON_TYPES", "error.invalid_lesson_types");
            }
        }
    }

    private record RoomImageUploadResultV2(String roomName, Long categoryId, String url) {}

    private static record CategoryCoverUploadResult(Long categoryId, String url) {}

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = "gymDetails", key = "#gymId"),
            @CacheEvict(cacheNames = "gym-images", key = "#gymId")
    })
    public void createGymStep5V2(Long gymId, MultipartFile coverPhoto, List<MultipartFile> categoryCovers, List<Long> categoryCoverCategoryIds, List<String> roomNames, List<Long> roomCategoryIds, List<MultipartFile> roomPhotos) {
        CompletableFuture<String> coverFuture = null;
        if (coverPhoto != null && !coverPhoto.isEmpty()) {
            MultipartFile validatedCover = fileStorageService.validateAndWrapImage(coverPhoto);
            coverFuture = CompletableFuture.supplyAsync(() ->
                            fileStorageService.saveFile(validatedCover, "/gyms/covers"),
                    imageUploadExecutor
            );
        }

        List<CompletableFuture<CategoryCoverUploadResult>> categoryCoverFutures = new ArrayList<>();
        if (categoryCovers != null && categoryCoverCategoryIds != null && categoryCovers.size() == categoryCoverCategoryIds.size() && !categoryCovers.isEmpty()) {
            for (int i = 0; i < categoryCovers.size(); i++) {
                MultipartFile file = categoryCovers.get(i);
                if (file == null || file.isEmpty()) continue;
                Long categoryId = categoryCoverCategoryIds.get(i);
                MultipartFile validatedFile = fileStorageService.validateAndWrapImage(file);
                categoryCoverFutures.add(CompletableFuture.supplyAsync(() -> {
                    String url = fileStorageService.saveFile(validatedFile, "/gyms/covers");
                    return new CategoryCoverUploadResult(categoryId, url);
                }, imageUploadExecutor));
            }
        }

        List<CompletableFuture<RoomImageUploadResultV2>> roomFutures = new ArrayList<>();
        if (roomNames != null && roomPhotos != null && roomNames.size() == roomPhotos.size() && !roomNames.isEmpty()) {
            for (int i = 0; i < roomPhotos.size(); i++) {
                MultipartFile originalFile = roomPhotos.get(i);
                if (originalFile == null || originalFile.isEmpty()) continue;

                String roomName = roomNames.get(i);
                Long categoryId = (roomCategoryIds != null && roomCategoryIds.size() > i) ? roomCategoryIds.get(i) : null;
                MultipartFile validatedFile = fileStorageService.validateAndWrapImage(originalFile);

                roomFutures.add(CompletableFuture.supplyAsync(() -> {
                    String url = fileStorageService.saveFile(validatedFile, "/gyms/rooms");
                    return new RoomImageUploadResultV2(roomName, categoryId, url);
                }, imageUploadExecutor));
            }
        }

        String coverUrl = null;
        if (coverFuture != null) {
            coverUrl = coverFuture.join();
        }

        List<CategoryCoverUploadResult> categoryCoverResults = categoryCoverFutures.stream()
                .map(CompletableFuture::join)
                .toList();

        List<RoomImageUploadResultV2> roomResults = roomFutures.stream()
                .map(CompletableFuture::join)
                .toList();

        final String finalCoverUrl = coverUrl;
        new TransactionTemplate(transactionManager).execute(status -> {
            if (finalCoverUrl != null) {
                updateCoverImageInternal(gymId, finalCoverUrl);
            }
            if (!categoryCoverResults.isEmpty()) {
                applyCategoryCoversUpdateInternal(gymId, categoryCoverResults);
            }
            if (!roomResults.isEmpty()) {
                applyRoomImagesUpdateInternalV2(gymId, roomResults);
            }
            completeStep5Internal(gymId);
            return null;
        });
    }

    @Transactional
    protected void applyCategoryCoversUpdateInternal(Long gymId, List<CategoryCoverUploadResult> results) {
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        for (CategoryCoverUploadResult res : results) {
            GymDescription desc = gymDescriptionRepository.findByGymIdAndCategoryId(gymId, res.categoryId())
                    .orElseGet(() -> {
                        Category category = categoryRepository.findById(res.categoryId()).orElse(null);
                        GymDescription newDesc = GymDescription.builder()
                                .gym(gym)
                                .category(category)
                                .build();
                        gym.getDescriptions().add(newDesc);
                        return newDesc;
                    });
            desc.setCoverImageUrl(res.url());
            gymDescriptionRepository.save(desc);
        }
    }

    @Transactional
    protected void applyRoomImagesUpdateInternalV2(Long gymId, List<RoomImageUploadResultV2> results) {
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));

        for (RoomImageUploadResultV2 res : results) {
            Category category = null;
            if (res.categoryId() != null) {
                category = categoryRepository.findById(res.categoryId()).orElse(null);
            }
            Room room = Room.builder()
                    .name(res.roomName())
                    .gym(gym)
                    .category(category)
                    .build();
            gym.getRooms().add(room);

            RoomImage roomImage = RoomImage.builder()
                    .room(room)
                    .pictureUrl(res.url())
                    .build();

            room.getImages().add(roomImage);
        }
        gymRepository.save(gym);
    }

    @Override
    public void validateStep5V2(MultipartFile coverPhoto, List<MultipartFile> categoryCovers, List<Long> categoryCoverCategoryIds, List<String> roomNames, List<Long> roomCategoryIds, List<MultipartFile> roomPhotos) {
        if (roomCategoryIds != null) {
            for (Long categoryId : roomCategoryIds) {
                if (categoryId != null) {
                    categoryRepository.findById(categoryId)
                            .orElseThrow(() -> new ResourceNotFoundException("CATEGORY_NOT_FOUND", "error.category_not_found"));
                }
            }
        }
        if (categoryCoverCategoryIds != null) {
            for (Long categoryId : categoryCoverCategoryIds) {
                if (categoryId != null) {
                    categoryRepository.findById(categoryId)
                            .orElseThrow(() -> new ResourceNotFoundException("CATEGORY_NOT_FOUND", "error.category_not_found"));
                }
            }
        }
        validateStep5(coverPhoto, roomNames, roomPhotos);
        if (categoryCovers != null) {
            for (MultipartFile file : categoryCovers) {
                if (file != null && !file.isEmpty()) {
                    fileStorageService.validateAndWrapImage(file);
                }
            }
        }
    }

    @Override
    @Transactional
    public void createGymStep6V2(Long gymId, GymCreateStep6RequestV2 request, List<MultipartFile> serviceIcons) {
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));

        validateStep(gym, 5);

        Map<Integer, String> iconUrlByIndex = uploadServiceIcons(serviceIcons);

        updateGymSubscriptionsInternalV2(gym, request, iconUrlByIndex);
        updateStep(gym, 5);
    }

    @Override
    public void validateStep6V2(GymCreateStep6RequestV2 request, List<MultipartFile> serviceIcons) {
        if (request.subscriptions() == null || request.subscriptions().isEmpty()) {
            throw new BadRequestException("SUBSCRIPTION_REQUIRED", "error.subscription_required");
        }

        for (GymCreateStep6SubscriptionRequestV2 subReq : request.subscriptions()) {
            if (!orderServiceGrpcClient.checkPackageExists(subReq.packageId())) {
                throw new BadRequestException("PACKAGE_NOT_FOUND", "error.package_not_found");
            }
            if (subReq.categoryId() != null) {
                categoryRepository.findById(subReq.categoryId())
                        .orElseThrow(() -> new ResourceNotFoundException("CATEGORY_NOT_FOUND", "error.category_not_found"));
            }
        }

        if (serviceIcons != null && !serviceIcons.isEmpty()) {
            for (MultipartFile icon : serviceIcons) {
                if (icon != null && !icon.isEmpty()) {
                    fileStorageService.validateAndWrapImage(icon);
                }
            }
        }
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = "gym-detail", allEntries = true)
    public void updateGymSubscriptionsV2(Long gymId, GymCreateStep6RequestV2 request) {
        Gym gym = gymRepository.findById(gymId).orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        updateGymSubscriptionsInternalV2(gym, request, Collections.emptyMap());
    }

    @Override
    @Transactional
    @org.springframework.cache.annotation.CacheEvict(cacheNames = {"gymDetails", "admin-gyms", "nearestGyms", "gym-listings", "gym-count-by-category", "gym-count-by-subscription"}, allEntries = true)
    public void updateGymInfoV2(Long gymId, az.fitnest.catalog.dto.request.GymInfoUpdateRequestV2 request) {
        Gym gym = gymRepository.findById(gymId)
                .orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));

        if (request.mainCategoryDetails() != null) {
            gym.getDescriptions().clear();
            List<Long> mainCategoryIds = request.mainCategoryDetails().stream().map(CategoryDetail::categoryId).toList();
            List<Long> subCategoryIds = request.subCategoryDetails() == null ? new ArrayList<>() : request.subCategoryDetails().stream().map(CategoryDetail::categoryId).toList();

            for (Long mainId : mainCategoryIds) {
                if (subCategoryIds.contains(mainId)) {
                    throw new BadRequestException("CATEGORIES_MUST_BE_DIFFERENT", "error.categories_must_be_different");
                }
            }

            List<Category> mainCategories = categoryRepository.findAllById(mainCategoryIds);
            if (mainCategories.size() != mainCategoryIds.size()) {
                throw new ResourceNotFoundException("MAIN_CATEGORY_NOT_FOUND", "error.main_category_not_found");
            }
            gym.setMainCategories(new java.util.HashSet<>(mainCategories));

            List<Category> subCategories = new java.util.ArrayList<>();
            if (!subCategoryIds.isEmpty()) {
                subCategories = categoryRepository.findAllById(subCategoryIds);
                if (subCategories.size() != subCategoryIds.size()) {
                    throw new ResourceNotFoundException("SUB_CATEGORY_NOT_FOUND", "error.sub_category_not_found");
                }
                gym.setSubCategories(new java.util.HashSet<>(subCategories));
            } else {
                gym.getSubCategories().clear();
            }

            // Save new descriptions
            for (CategoryDetail detail : request.mainCategoryDetails()) {
                Category category = mainCategories.stream().filter(c -> c.getId().equals(detail.categoryId())).findFirst().orElse(null);
                if (category != null) {
                    GymDescription gymDesc = GymDescription.builder()
                            .gym(gym)
                            .category(category)
                            .phone(detail.phone() != null ? PhoneUtil.normalize(detail.phone()) : null)
                            .description(detail.description())
                            .coverImageUrl(detail.coverImageUrl())
                            .build();
                    gym.getDescriptions().add(gymDesc);
                }
            }
            if (request.subCategoryDetails() != null) {
                for (CategoryDetail detail : request.subCategoryDetails()) {
                    Category category = subCategories.stream().filter(c -> c.getId().equals(detail.categoryId())).findFirst().orElse(null);
                    if (category != null) {
                        GymDescription gymDesc = GymDescription.builder()
                                .gym(gym)
                                .category(category)
                                .phone(detail.phone() != null ? PhoneUtil.normalize(detail.phone()) : null)
                                .description(detail.description())
                                .coverImageUrl(detail.coverImageUrl())
                                .build();
                        gym.getDescriptions().add(gymDesc);
                    }
                }
            }
        }

        if (request.name() != null) gym.setName(request.name());
        if (request.description() != null) gym.setDescription(request.description());
        if (request.phone() != null) gym.setPhone(request.phone());
        if (request.email() != null) gym.setEmail(request.email().isBlank() ? null : request.email());
        if (request.hasSubcategories() != null) gym.setHasSubcategories(request.hasSubcategories());

        if (gym.getAddress() == null) {
            gym.setAddress(new Address());
        }

        if (request.city() != null) gym.getAddress().setCity(request.city());
        if (request.address() != null) gym.getAddress().setAddressText(request.address());
        if (request.latitude() != null) gym.getAddress().setLatitude(request.latitude());
        if (request.longitude() != null) gym.getAddress().setLongitude(request.longitude());
        if (request.altitude() != null) gym.getAddress().setAltitude(request.altitude());

        gymRepository.save(gym);

        if (request.name() != null) {
            translationService.autoTranslateAndSave("GYM", gym.getId().toString(), "name", request.name());
        }
        if (request.description() != null) {
            translationService.autoTranslateAndSave("GYM", gym.getId().toString(), "description", request.description());
        }
        if (gym.getAddress() != null) {
            if (request.address() != null) {
                translationService.autoTranslateAndSave("GYM", gym.getId().toString(), "addressText", gym.getAddress().getAddressText());
            }
            if (request.city() != null) {
                translationService.autoTranslateAndSave("GYM", gym.getId().toString(), "city", gym.getAddress().getCity());
            }
        }
    }

    @Override
    public Long createGymCompleteV2(az.fitnest.catalog.dto.request.GymCreateCompleteRequestV2 request, MultipartFile coverPhoto,
                                    List<MultipartFile> categoryCovers, List<Long> categoryCoverCategoryIds,
                                    List<MultipartFile> trainerPhotos, List<MultipartFile> roomPhotos,
                                    List<MultipartFile> serviceIcons) {

        GymCreateStep1RequestV2 step1 = GymCreateStep1RequestV2.builder()
                .mainCategoryDetails(request.mainCategoryDetails())
                .subCategoryDetails(request.subCategoryDetails())
                .name(request.name())
                .phone(request.phone())
                .description(request.description())
                .email(request.email())
                .lessonTypeIds(request.lessonTypeIds())
                .build();
        validateStep1V2(step1);

        if (request.trainers() != null && !request.trainers().isEmpty()) {
            List<String> emails = request.trainers().stream().map(GymCreateCompleteRequestV2.TrainerCreateData::email).filter(e -> e != null && !e.isBlank()).toList();
            List<String> phones = request.trainers().stream().map(GymCreateCompleteRequestV2.TrainerCreateData::phone).filter(p -> p != null && !p.isBlank()).toList();
            validateStep2(emails, phones);
        }

        GymCreateStep2Request step3 = GymCreateStep2Request.builder()
                .generalWorkHours(request.generalWorkHours())
                .workHoursWoman(request.workHoursWoman())
                .workHoursMan(request.workHoursMan())
                .restDays(request.restDays())
                .build();
        validateStep3(step3);

        GymCreateStep3Request step4 = GymCreateStep3Request.builder()
                .latitude(request.latitude())
                .longitude(request.longitude())
                .build();
        validateStep4(step4);

        validateStep5V2(coverPhoto, categoryCovers, categoryCoverCategoryIds, request.roomNames(), request.roomCategoryIds(), roomPhotos);

        GymCreateStep6RequestV2 step6 = GymCreateStep6RequestV2.builder()
                .subscriptions(request.subscriptions())
                .build();
        validateStep6V2(step6, serviceIcons);

        GymCreateStep7Request step7 = GymCreateStep7Request.builder()
                .admins(request.admins())
                .build();
        validateStep7(step7);

        GeocodingResponse geocoding = reverseGeocodingService.reverseGeocode(request.latitude(), request.longitude());

        CompletableFuture<String> coverFuture = null;
        if (coverPhoto != null && !coverPhoto.isEmpty()) {
            MultipartFile validatedCover = fileStorageService.validateAndWrapImage(coverPhoto);
            coverFuture = CompletableFuture.supplyAsync(() ->
                    fileStorageService.saveFile(validatedCover, "/gyms/covers"), imageUploadExecutor);
        }

        List<CompletableFuture<CategoryCoverUploadResult>> categoryCoverFutures = new ArrayList<>();
        if (categoryCovers != null && categoryCoverCategoryIds != null && categoryCovers.size() == categoryCoverCategoryIds.size()) {
            for (int i = 0; i < categoryCovers.size(); i++) {
                MultipartFile file = categoryCovers.get(i);
                if (file == null || file.isEmpty()) continue;
                Long categoryId = categoryCoverCategoryIds.get(i);
                MultipartFile validated = fileStorageService.validateAndWrapImage(file);
                categoryCoverFutures.add(CompletableFuture.supplyAsync(() -> {
                    String url = fileStorageService.saveFile(validated, "/gyms/covers");
                    return new CategoryCoverUploadResult(categoryId, url);
                }, imageUploadExecutor));
            }
        }

        List<CompletableFuture<String>> trainerPhotoFutures = new ArrayList<>();
        if (trainerPhotos != null) {
            for (MultipartFile photo : trainerPhotos) {
                if (photo != null && !photo.isEmpty()) {
                    MultipartFile validated = fileStorageService.validateAndWrapImage(photo);
                    trainerPhotoFutures.add(CompletableFuture.supplyAsync(() ->
                            fileStorageService.saveFile(validated, "/gyms/trainers"), imageUploadExecutor));
                } else {
                    trainerPhotoFutures.add(CompletableFuture.completedFuture(null));
                }
            }
        }

        List<CompletableFuture<RoomImageUploadResultV2>> roomFutures = new ArrayList<>();
        if (request.roomNames() != null && roomPhotos != null && request.roomNames().size() == roomPhotos.size()) {
            for (int i = 0; i < roomPhotos.size(); i++) {
                MultipartFile file = roomPhotos.get(i);
                if (file == null || file.isEmpty()) continue;
                String roomName = request.roomNames().get(i);
                Long categoryId = (request.roomCategoryIds() != null && request.roomCategoryIds().size() > i) ? request.roomCategoryIds().get(i) : null;
                MultipartFile validated = fileStorageService.validateAndWrapImage(file);
                roomFutures.add(CompletableFuture.supplyAsync(() -> {
                    String url = fileStorageService.saveFile(validated, "/gyms/rooms");
                    return new RoomImageUploadResultV2(roomName, categoryId, url);
                }, imageUploadExecutor));
            }
        }

        CompletableFuture<Map<Integer, String>> serviceIconsFuture = CompletableFuture.supplyAsync(() ->
                uploadServiceIcons(serviceIcons), imageUploadExecutor);

        List<CompletableFuture<GymAdminCreateResult>> adminFutures = request.admins().stream()
                .map(adminReq -> CompletableFuture.supplyAsync(() -> {
                    Long userId = identityServiceGrpcClient.createGymAdmin(
                            adminReq.name(), adminReq.surname(),
                            PhoneUtil.normalize(adminReq.phoneNumber()),
                            adminReq.email(), adminReq.password()
                    );
                    return new GymAdminCreateResult(adminReq, userId);
                }, imageUploadExecutor))
                .toList();

        String finalCoverUrl = coverFuture != null ? coverFuture.join() : null;
        List<CategoryCoverUploadResult> categoryCoverResults = categoryCoverFutures.stream().map(CompletableFuture::join).toList();
        List<String> trainerPhotoUrls = trainerPhotoFutures.stream().map(CompletableFuture::join).toList();
        List<RoomImageUploadResultV2> roomResults = roomFutures.stream().map(CompletableFuture::join).toList();
        List<GymAdminCreateResult> adminResults = adminFutures.stream().map(CompletableFuture::join).toList();
        Map<Integer, String> iconUrlByIndex = serviceIconsFuture.join();

        Long gymId = new TransactionTemplate(transactionManager).execute(status -> {
            if (request.mainCategoryDetails() == null || request.mainCategoryDetails().isEmpty()) {
                throw new BadRequestException("MAIN_CATEGORY_REQUIRED", "error.main_category_required");
            }
            List<Long> mainCategoryIds = request.mainCategoryDetails().stream().map(CategoryDetail::categoryId).toList();
            List<Long> subCategoryIds = request.subCategoryDetails() == null ? new ArrayList<>() : request.subCategoryDetails().stream().map(CategoryDetail::categoryId).toList();

            for (Long mainId : mainCategoryIds) {
                if (subCategoryIds.contains(mainId)) {
                    throw new BadRequestException("CATEGORIES_MUST_BE_DIFFERENT", "error.categories_must_be_different");
                }
            }
            List<Category> mainCategories = categoryRepository.findAllById(mainCategoryIds);
            if (mainCategories.size() != mainCategoryIds.size()) {
                throw new ResourceNotFoundException("MAIN_CATEGORY_NOT_FOUND", "error.main_category_not_found");
            }

            List<Category> subCategories = new java.util.ArrayList<>();
            if (!subCategoryIds.isEmpty()) {
                subCategories = categoryRepository.findAllById(subCategoryIds);
                if (subCategories.size() != subCategoryIds.size()) {
                    throw new ResourceNotFoundException("SUB_CATEGORY_NOT_FOUND", "error.sub_category_not_found");
                }
            }

            Category mainCategory = mainCategories.isEmpty() ? null : mainCategories.get(0);

            Gym gym = new Gym();
            gym.setName(request.name());
            gym.setDescription(request.description());
            gym.setPhone(PhoneUtil.normalize(request.phone()));
            gym.setEmail(request.email() != null && request.email().isBlank() ? null : request.email());
            gym.setMainCategories(new java.util.HashSet<>(mainCategories));
            gym.setSubCategories(new java.util.HashSet<>(subCategories));
            gym.setHasSubcategories(request.hasSubcategories() != null && request.hasSubcategories());

            // Save descriptions
            for (CategoryDetail detail : request.mainCategoryDetails()) {
                Category category = mainCategories.stream().filter(c -> c.getId().equals(detail.categoryId())).findFirst().orElse(null);
                if (category != null) {
                    String coverUrl = categoryCoverResults.stream()
                            .filter(r -> r.categoryId().equals(detail.categoryId()))
                            .map(CategoryCoverUploadResult::url)
                            .findFirst()
                            .orElse(detail.coverImageUrl());

                    GymDescription gymDesc = GymDescription.builder()
                            .gym(gym)
                            .category(category)
                            .phone(detail.phone() != null ? PhoneUtil.normalize(detail.phone()) : null)
                            .description(detail.description())
                            .coverImageUrl(coverUrl)
                            .build();
                    gym.getDescriptions().add(gymDesc);
                }
            }
            if (request.subCategoryDetails() != null) {
                for (CategoryDetail detail : request.subCategoryDetails()) {
                    Category category = subCategories.stream().filter(c -> c.getId().equals(detail.categoryId())).findFirst().orElse(null);
                    if (category != null) {
                        String coverUrl = categoryCoverResults.stream()
                                .filter(r -> r.categoryId().equals(detail.categoryId()))
                                .map(CategoryCoverUploadResult::url)
                                .findFirst()
                                .orElse(detail.coverImageUrl());

                        GymDescription gymDesc = GymDescription.builder()
                                .gym(gym)
                                .category(category)
                                .phone(detail.phone() != null ? PhoneUtil.normalize(detail.phone()) : null)
                                .description(detail.description())
                                .coverImageUrl(coverUrl)
                                .build();
                        gym.getDescriptions().add(gymDesc);
                    }
                }
            }

            updateWorkHours(gym.getGeneralWorkHours(), request.generalWorkHours());
            updateWorkHours(gym.getWorkHoursWoman(), request.workHoursWoman());
            updateWorkHours(gym.getWorkHoursMan(), request.workHoursMan());

            if (request.restDays() != null) {
                Set<GymWorkHourPeriod> restDays = request.restDays().stream()
                        .flatMap(r -> az.fitnest.catalog.mapper.GymMapper.expandPeriods(r.period()).stream())
                        .collect(Collectors.toSet());
                gym.getRestDays().addAll(restDays);
            }

            Address address = new Address();
            address.setLatitude(request.latitude());
            address.setLongitude(request.longitude());
            address.setAltitude(request.altitude());
            if (geocoding != null) {
                address.setAddressText(geocoding.addressText());
                address.setCity(geocoding.city());
            }
            gym.setAddress(address);

            if (finalCoverUrl != null) {
                gym.setCoverImageUrl(finalCoverUrl);
            }

            gym.setStatus(GymStatus.ACTIVE);
            gym.setCreationStep(7);

            Gym savedGym = gymRepository.save(gym);
            Long savedGymId = savedGym.getId();

            translationService.autoTranslateAndSave("GYM", savedGymId.toString(), "name", request.name());
            translationService.autoTranslateAndSave("GYM", savedGymId.toString(), "description", request.description());
            if (savedGym.getAddress() != null) {
                translationService.autoTranslateAndSave("GYM", savedGymId.toString(), "addressText", savedGym.getAddress().getAddressText());
                translationService.autoTranslateAndSave("GYM", savedGymId.toString(), "city", savedGym.getAddress().getCity());
            }

            if (request.trainers() != null && !request.trainers().isEmpty()) {
                List<String> names = request.trainers().stream().map(GymCreateCompleteRequestV2.TrainerCreateData::name).toList();
                List<String> surnames = request.trainers().stream().map(GymCreateCompleteRequestV2.TrainerCreateData::surname).toList();
                List<Long> professionIds = request.trainers().stream().map(GymCreateCompleteRequestV2.TrainerCreateData::professionId).toList();
                List<String> emails = request.trainers().stream().map(t -> t.email() != null ? t.email() : "").toList();
                List<String> phones = request.trainers().stream().map(t -> t.phone() != null ? t.phone() : "").toList();
                List<String> lessonTypesPerTrainer = request.trainers().stream().map(t -> t.lessonTypeIds() != null ? t.lessonTypeIds() : "").toList();

                gymTrainerService.addTrainersWithUrls(savedGymId, names, surnames, professionIds, emails, phones, trainerPhotoUrls, lessonTypesPerTrainer);
            }

            if (!roomResults.isEmpty()) {
                for (RoomImageUploadResultV2 res : roomResults) {
                    Category category = null;
                    if (res.categoryId() != null) {
                        category = categoryRepository.findById(res.categoryId()).orElse(null);
                    }
                    Room room = Room.builder()
                            .name(res.roomName())
                            .gym(savedGym)
                            .category(category)
                            .build();
                    savedGym.getRooms().add(room);

                    RoomImage roomImage = RoomImage.builder()
                            .room(room)
                            .pictureUrl(res.url())
                            .build();
                    room.getImages().add(roomImage);
                }
                gymRepository.save(savedGym);
            }

            updateGymSubscriptionsInternalV2(savedGym, step6, iconUrlByIndex);

            for (GymAdminCreateResult res : adminResults) {
                String role = (res.req().role() != null && !res.req().role().trim().isEmpty()) ? res.req().role() : "Super admin";
                az.fitnest.catalog.model.entity.GymAdmin saved = gymAdminRepository.save(
                        az.fitnest.catalog.mapper.GymMapper.toAdminEntity(savedGym, res.req(), res.userId(), role)
                );
                translationService.autoTranslateAndSave("GymAdmin", saved.getId().toString(), "name", saved.getName());
                translationService.autoTranslateAndSave("GymAdmin", saved.getId().toString(), "surname", saved.getSurname());
            }

            return savedGymId;
        });

        gymQrCodeService.generateAndSaveQrCode(gymId);
        gymActivationNotifier.notifyNewActiveGym(gymId, request.name());

        return gymId;
    }

    private void updateGymSubscriptionsInternalV2(
            Gym gym,
            GymCreateStep6RequestV2 request,
            Map<Integer, String> iconUrlByIndex) {

        Long gymId = gym.getId();
        gym.getSubscriptions().clear();
        gymRepository.saveAndFlush(gym);

        Set<String> processedPairs = new HashSet<>();
        Map<String, SupportedService> nameToService = new HashMap<>();
        int globalIconIndex = 0;

        for (GymCreateStep6SubscriptionRequestV2 subReq : request.subscriptions()) {
            String pairKey = subReq.packageId() + "_" + subReq.categoryId();
            if (!processedPairs.add(pairKey)) continue;

            Category category = categoryRepository.findById(subReq.categoryId()).orElse(null);

            GymSubscription subscription = new GymSubscription();
            subscription.setGym(gym);
            subscription.setPackageId(subReq.packageId());
            subscription.setDailyPrice(subReq.dailyPrice());
            subscription.setCategory(category);

            Set<SupportedService> services = new HashSet<>();

            if (subReq.supportedServicesId() != null && !subReq.supportedServicesId().isEmpty()) {
                List<SupportedService> existingServices = supportedServiceRepository
                        .findAllById(subReq.supportedServicesId())
                        .stream()
                        .filter(s -> s.getGymId() == null || s.getGymId().equals(gymId))
                        .toList();
                services.addAll(existingServices);
            }

            if (subReq.customServices() != null && !subReq.customServices().isEmpty()) {
                for (String customServiceName : subReq.customServices()) {
                    if (customServiceName == null || customServiceName.trim().isEmpty()) {
                        globalIconIndex++;
                        continue;
                    }

                    String trimmedName = customServiceName.trim();
                    String iconUrl = iconUrlByIndex.get(globalIconIndex);
                    globalIconIndex++;

                    SupportedService service = nameToService.get(trimmedName.toLowerCase());
                    if (service == null) {
                        Optional<SupportedService> existingOpt = supportedServiceRepository
                                .findByNameIgnoreCaseAndGymId(trimmedName, gymId);

                        if (existingOpt.isPresent()) {
                            service = existingOpt.get();
                            if (iconUrl != null) {
                                if (service.getIconUrl() != null) {
                                    fileStorageService.deleteFilesAfterCommit(
                                            List.of(service.getIconUrl())
                                    );
                                }
                                service.setIconUrl(iconUrl);
                                service = supportedServiceRepository.save(service);
                            }
                        } else {
                            service = new SupportedService();
                            service.setName(trimmedName);
                            service.setGymId(gymId);
                            if (iconUrl != null) {
                                service.setIconUrl(iconUrl);
                            }
                            service = supportedServiceRepository.save(service);
                            translationService.autoTranslateAndSave(
                                    "SupportedService",
                                    service.getId().toString(),
                                    "name",
                                    service.getName()
                            );
                        }
                        nameToService.put(trimmedName.toLowerCase(), service);
                    }
                    services.add(service);
                }
            }

            subscription.setSupportedServices(services);
            gym.getSubscriptions().add(subscription);
        }

        gymRepository.save(gym);
    }

    private void verifyGymOwnership(Long gymId) {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            throw new az.fitnest.catalog.exception.UnauthorizedException("Unauthorized");
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
}

