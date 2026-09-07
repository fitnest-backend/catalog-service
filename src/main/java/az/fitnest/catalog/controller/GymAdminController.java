package az.fitnest.catalog.controller;
import az.fitnest.catalog.dto.response.GymSubscriptionsAdminResponseV2;
import az.fitnest.catalog.dto.response.GymInfoAdminResponseV2;
import az.fitnest.catalog.dto.request.GymInfoUpdateRequestV2;
import az.fitnest.catalog.dto.request.GymCreateStep6RequestV2;
import az.fitnest.catalog.dto.request.GymCreateStep1RequestV2;
import az.fitnest.catalog.dto.request.GymCreateCompleteRequestV2;

import az.fitnest.catalog.dto.PaginatedResponse;
import az.fitnest.catalog.dto.request.GymCreateCompleteRequest;
import az.fitnest.catalog.dto.request.GymCreateStep6Request;
import az.fitnest.catalog.dto.request.GymRequest;
import az.fitnest.catalog.dto.request.GymSubscriptionBenefitsUpdateRequest;
import az.fitnest.catalog.dto.request.TrainerRequest;
import az.fitnest.catalog.dto.response.GeocodingResponse;
import az.fitnest.catalog.dto.response.GymCreateStep1Response;
import az.fitnest.catalog.dto.response.GymEntranceHistoryAdminResponse;
import az.fitnest.catalog.dto.response.GymQrResponse;
import az.fitnest.catalog.dto.response.GymReviewResponse;
import az.fitnest.catalog.dto.response.GymTrainerResponse;
import az.fitnest.catalog.dto.response.GymTrainerResponseV2;
import az.fitnest.catalog.service.GymReadService;
import az.fitnest.catalog.service.GymReviewService;
import az.fitnest.catalog.service.GymTrainerService;
import az.fitnest.catalog.service.GymWriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Gym Admin", description = "İdman zallarını idarə etmək üçün administrativ ucluqlar. Bu ucluqlar yalnız ADMIN və SUPER_ADMIN rollarına malik istifadəçilər tərəfindən istifadə edilə bilər.")
@SecurityRequirement(name = "bearerAuth")
public class GymAdminController {

    private final GymWriteService gymWriteService;
    private final GymReviewService gymReviewService;
    private final GymTrainerService gymTrainerService;
    private final GymReadService gymReadService;
    private final az.fitnest.catalog.service.GymEntranceService gymEntranceService;

    @Operation(summary = "Yeni idman zalı yaradın", description = "Sistemə yeni idman zalı əlavə edir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/v1/admin/gyms")
    public ResponseEntity<Void> createGym(@Valid @RequestBody GymRequest request) {
        gymWriteService.createGym(request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Operation(summary = "İdman zalını yeniləyin", description = "Mövcud idman zalının məlumatlarını yeniləyir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/v1/admin/gyms/{id}")
    public ResponseEntity<Void> updateGym(@PathVariable Long id, @Valid @RequestBody GymRequest request) {
        gymWriteService.updateGym(id, request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Zal məlumatlarını alın", description = "İdman zalı üçün info-tab məlumatlarını gətirir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasAnyRole('ADMIN', 'GYM_SUPER_ADMIN', 'GYM_ADMIN')")
    @GetMapping("/v1/admin/gyms/{id}/details")
    public ResponseEntity<az.fitnest.catalog.dto.response.GymInfoAdminResponse> getGymDetails(@PathVariable("id") Long gymId) {
        return ResponseEntity.ok(gymReadService.getGymDetailsAdmin(gymId));
    }

    @Operation(summary = "Zal iş saatlarını alın", description = "İdman zalı üçün iş saatları məlumatlarını gətirir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasAnyRole('ADMIN', 'GYM_SUPER_ADMIN', 'GYM_ADMIN')")
    @GetMapping("/v1/admin/gyms/{id}/work-hours")
    public ResponseEntity<az.fitnest.catalog.dto.response.GymWorkHoursAdminResponse> getGymWorkHours(@PathVariable("id") Long gymId) {
        return ResponseEntity.ok(gymReadService.getGymWorkHours(gymId));
    }

    @Operation(summary = "İdman zalının iş saatlarını yeniləyin", description = "İdman zalının iş saatlarını yeniləyir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/v1/admin/gyms/{id}/work-hours")
    public ResponseEntity<Void> updateGymWorkHours(@PathVariable("id") Long id, @Valid @RequestBody az.fitnest.catalog.dto.request.GymCreateStep2Request request) {
        gymWriteService.updateGymWorkHours(id, request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "İdman zalı QR kod URL-ni əldə edin", description = "İdman zalının QR kod şəkli üçün yayım URL-ni qaytarır. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasAnyRole('ADMIN', 'GYM_SUPER_ADMIN', 'GYM_ADMIN')")
    @GetMapping("/v1/admin/gyms/{id}/qr")
    public ResponseEntity<GymQrResponse> getGymQrUrl(@PathVariable Long id) {
        return ResponseEntity.ok(new GymQrResponse(this.gymReadService.getGymQrUrl(id)));
    }

    @Operation(summary = "Zal abunəliklərini alın", description = "İdman zalı üçün aktiv abunəlik və xidmət məlumatlarını gətirir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/v1/admin/gyms/{id}/subscriptions")
    public ResponseEntity<az.fitnest.catalog.dto.response.GymSubscriptionsAdminResponse> getGymSubscriptions(@PathVariable("id") Long gymId) {
        return ResponseEntity.ok(gymReadService.getGymSubscriptions(gymId));
    }

    @Operation(summary = "Zal məlumatlarını yeniləyin", description = "İdman zalı üçün yalnız info-tab məlumatlarını yeniləyir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/v1/admin/gyms/{id}/details")
    public ResponseEntity<Void> updateGymDetails(@PathVariable("id") Long gymId, @Valid @RequestBody az.fitnest.catalog.dto.request.GymInfoUpdateRequest request) {
        gymWriteService.updateGymInfo(gymId, request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "İdman zalı üçün mövcud abunəlik planlarını aktivləşdirin", description = "İdman zalı üçün göstərilən abunəlik planlarını aktivləşdirir. Mövcud planların üstünlükləri qorunur, siyahıda olmayanlar isə silinir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/v1/admin/gyms/{id}/subscriptions/enable")
    public ResponseEntity<Void> enableGymSubscription(
            @PathVariable("id") Long gymId,
            @RequestParam("subscriptionId") Long subscriptionId) {
        gymWriteService.enableGymSubscription(gymId, subscriptionId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Aktivləşdirilmiş abunəlik üçün üstünlükləri yeniləyin", description = "İdman zalı üçün artıq aktivləşdirilmiş olan müəyyən bir abunəliyin üstünlüklərini (benefits) yeniləyir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/v1/admin/gyms/{id}/subscriptions/{planId}/benefits")
    public ResponseEntity<Void> updateGymSubscriptionBenefits(@PathVariable Long id, @PathVariable Long planId,
                                                              @Valid @RequestBody GymSubscriptionBenefitsUpdateRequest request) {
        gymWriteService.updateGymSubscriptionBenefits(id, planId, request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "İdman zalına aid rəyləri alın", description = "İdman zalı üçün bütün rəylərin siyahısını gətirir. Status üzrə filtrləmə mümkündür.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/v1/admin/gyms/{id}/reviews")
    public ResponseEntity<PaginatedResponse<az.fitnest.catalog.dto.response.GymReviewResponse>> getGymReviews(
            @PathVariable("id") Long gymId,
            @RequestParam(required = false) az.fitnest.catalog.model.enums.ReviewStatus status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "newest") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ResponseEntity.ok(gymReviewService.getGymReviewsAdmin(gymId, status, search, page, pageSize, sort));
    }

    @Operation(summary = "Rəy detallarını alın", description = "Müəyyən bir rəyin detallarını gətirir.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/v1/admin/gyms/reviews/{reviewId}")
    public ResponseEntity<az.fitnest.catalog.dto.response.GymReviewResponse> getReviewDetail(@PathVariable Long reviewId) {
        return ResponseEntity.ok(gymReviewService.getReviewDetail(reviewId));
    }

    @Operation(summary = "İdman zalını silin", description = "İdman zalını sistemdən silir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/v1/admin/gyms/{id}")
    public ResponseEntity<Void> deleteGym(@PathVariable Long id) {
        gymWriteService.deleteGym(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "İdman zalına otaq şəkli əlavə edin", description = "İdman zalı üçün otaq şəkilləri yükləyir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/v1/admin/gyms/{id}/rooms", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> addRoomImages(
            @PathVariable Long id,
            @RequestParam("roomNames") java.util.List<String> roomNames,
            @RequestParam("files") java.util.List<MultipartFile> files) {
        if (roomNames == null || files == null || roomNames.size() != files.size()) {
            return ResponseEntity.badRequest().build();
        }
        gymWriteService.addRoomImages(id, roomNames, files);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "İdman zalının bütün otaqlarını silin", description = "İdman zalı üçün bütün otaqları və onların şəkillərini silir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/v1/admin/gyms/{id}/rooms")
    public ResponseEntity<Void> deleteAllGymRooms(@PathVariable Long id) {
        gymWriteService.deleteAllGymRooms(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "İdman zalının müəyyən bir otağını silin", description = "İdman zalı üçün göstərilən otağı və onun şəkillərini silir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/v1/admin/gyms/{id}/rooms/{roomId}")
    public ResponseEntity<Void> deleteGymRoomById(@PathVariable Long id, @PathVariable Long roomId) {
        gymWriteService.deleteGymRoomById(id, roomId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "İdman zalının müəyyən bir otaq şəklini silin", description = "İdman zalının otaqlarına aid olan müəyyən bir şəkli silir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/v1/admin/gyms/{id}/rooms/images/{imageId}")
    public ResponseEntity<Void> deleteRoomImageById(@PathVariable Long id, @PathVariable Long imageId) {
        gymWriteService.deleteRoomImageById(id, imageId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "İdman zalının müəyyən bir otağının adını yeniləyin", description = "İdman zalı üçün göstərilən otağın adını yeniləyir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/v1/admin/gyms/{id}/rooms/{roomId}/name")
    public ResponseEntity<Void> updateRoomName(
            @PathVariable Long id,
            @PathVariable Long roomId,
            @RequestParam("name") String name) {
        gymWriteService.updateRoomName(id, roomId, name);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "İdman zalı üz qabığı şəklini yeniləyin", description = "İdman zalı üçün əsas profil şəklini yükləyir və ya yeniləyir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/v1/admin/gyms/{id}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> updateCoverImage(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        gymWriteService.updateCoverImage(id, file);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Bütün idman zallarını silin (Kritik)", description = "Sistemdəki BÜTÜN idman zallarını silir. Bu əməliyyat üçün ADMIN rolu tələb olunur və bu hərəkət geri qaytarıla bilməz.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/v1/admin/gyms/all")
    public ResponseEntity<Void> deleteAllGyms() {
        gymWriteService.deleteAllGyms();
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Mövcud olmayan və ya təsdiqlənməmiş rəyi təsdiqləyin", description = "İdman zalına verilmiş rəyi təsdiqləyir və ümumi reytinqə daxil edir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/v1/admin/gyms/reviews/{reviewId}/approve")
    public ResponseEntity<Void> approveReview(@PathVariable Long reviewId) {
        gymReviewService.approveReview(reviewId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Rəyi rədd edin", description = "İdman zalına verilmiş rəyi rədd edir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/v1/admin/gyms/reviews/{reviewId}/reject")
    public ResponseEntity<Void> rejectReview(@PathVariable Long reviewId) {
        gymReviewService.rejectReview(reviewId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Təsdiq gözləyən rəyləri alın", description = "Sistemdə təsdiq gözləyən (PENDING) rəylərin siyahısını gətirir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/v1/admin/gyms/reviews/pending")
    public ResponseEntity<PaginatedResponse<GymReviewResponse>> getPendingReviews(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ResponseEntity.ok(gymReviewService.getPendingReviews(page, pageSize));
    }

    @Operation(summary = "Bütün abunəlikləri silin", description = "İdman zalı üçün bütün abunəlikləri silir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/v1/admin/gyms/{id}/subscriptions")
    public ResponseEntity<Void> deleteAllGymSubscriptions(@PathVariable("id") Long gymId) {
        gymWriteService.deleteAllGymSubscriptions(gymId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Abunəliyi silin", description = "İdman zalı üçün müəyyən bir abunəliyi silir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/v1/admin/gyms/{id}/subscriptions/{subscriptionId}")
    public ResponseEntity<Void> deleteGymSubscriptionById(@PathVariable("id") Long gymId,
                                                          @PathVariable("subscriptionId") Long subscriptionId) {
        gymWriteService.deleteGymSubscriptionById(gymId, subscriptionId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "İdman zalı məşqçilərini əldə edin", description = "İdman zalında çalışan məşqçilərin səhifələnmiş siyahısını qaytarır. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasAnyRole('ADMIN', 'GYM_SUPER_ADMIN', 'GYM_ADMIN')")
    @GetMapping("/v1/admin/gyms/{id}/trainers")
    public ResponseEntity<PaginatedResponse<GymTrainerResponse>> getTrainers(
            @PathVariable("id") Long gymId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(value = "sort_dir", defaultValue = "DESC") az.fitnest.catalog.dto.SortDirection sortDir) {
        int safePageSize = Math.min(pageSize, 100);
        return ResponseEntity.ok(gymTrainerService.getTrainers(gymId, page, safePageSize, sortDir.name().toLowerCase()));
    }

    @Operation(summary = "İdman zalı üçün təlimçi yaradın", description = "İdman zalına yeni təlimçi əlavə edir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/v1/admin/gyms/{id}/trainers", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> addTrainer(
            @PathVariable("id") Long gymId,
            @RequestParam("name") String name,
            @RequestParam("surname") String surname,
            @RequestParam(value = "professionId", required = false) Long professionId,
            @RequestParam(value = "phone", required = false) String phone,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "photo", required = false) MultipartFile photo,
            @RequestParam(value = "lessonTypeIds", required = false) List<Long> lessonTypeIds) {
        TrainerRequest request = new TrainerRequest(name, surname, professionId, phone, email, lessonTypeIds);
        gymTrainerService.addTrainer(gymId, request, photo);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Operation(summary = "Təlimçini yeniləyin", description = "İdman zalına aid təlimçinin məlumatlarını yeniləyir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping(value = "/v1/admin/gyms/{id}/trainers/{trainerId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> updateTrainer(
            @PathVariable("id") Long gymId,
            @PathVariable("trainerId") Long trainerId,
            @RequestParam("name") String name,
            @RequestParam("surname") String surname,
            @RequestParam(value = "professionId", required = false) Long professionId,
            @RequestParam(value = "phone", required = false) String phone,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "photo", required = false) MultipartFile photo,
            @RequestParam(value = "lessonTypeIds", required = false) List<Long> lessonTypeIds) {
        TrainerRequest request = new TrainerRequest(name, surname, professionId, phone, email, lessonTypeIds);
        gymTrainerService.updateTrainer(gymId, trainerId, request, photo);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Təlimçini silin", description = "İdman zalına aid təlimçini silir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/v1/admin/gyms/{id}/trainers/{trainerId}")
    public ResponseEntity<Void> deleteTrainer(@PathVariable("id") Long gymId,
                                              @PathVariable("trainerId") Long trainerId) {
        gymTrainerService.deleteTrainer(gymId, trainerId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Təlimçinin şəklini yeniləyin", description = "İdman zalına aid təlimçi üçün profil şəklini yükləyir və ya yeniləyir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/v1/admin/gyms/{id}/trainers/{trainerId}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> updateTrainerPhoto(
            @PathVariable("id") Long gymId,
            @PathVariable("trainerId") Long trainerId,
            @RequestParam("file") MultipartFile file) {
        gymTrainerService.updateTrainerPhoto(gymId, trainerId, file);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Giriş tarixçəsini silin", description = "Bütün idman zallarının giriş tarixçəsini silir və gəlirləri sıfırlayır. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/v1/admin/gyms/history")
    public ResponseEntity<Void> deleteGymEntranceHistory() {
        gymWriteService.deleteGymEntranceHistory();
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Konkret idman zalı və paketin giriş tarixçəsini silin", description = "Konkret idman zalı və paket üzrə giriş tarixçəsini silir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/v1/admin/gyms/{id}/packages/{packageId}/history")
    public ResponseEntity<Void> deleteGymEntranceHistory(
            @PathVariable("id") Long gymId,
            @PathVariable("packageId") Long packageId) {
        gymWriteService.deleteGymEntranceHistory(gymId, packageId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "İdman zalı giriş tarixçəsini alın", description = "İdman zalı üçün bütün giriş cəhdlərinin (uğurlu/uğursuz) siyahısını gətirir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/v1/admin/gyms/{id}/history")
    public ResponseEntity<List<GymEntranceHistoryAdminResponse>> getGymEntranceHistory(@PathVariable("id") Long gymId) {
        return ResponseEntity.ok(gymReadService.getGymEntranceHistory(gymId));
    }

    @Operation(summary = "Seçilmiş müştəri girişlərini silin", description = "İdman zalı üzrə seçilmiş giriş tarixçəsi qeydlərini silir. Yalnız ADMIN rolu icazəlidir.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/v1/admin/gyms/{id}/history")
    public ResponseEntity<Void> deleteGymEntranceHistoryByIds(
            @PathVariable("id") Long gymId,
            @RequestBody @Valid az.fitnest.catalog.dto.request.DeleteGymEntranceHistoryRequest request) {
        gymWriteService.deleteGymEntranceHistoryByIds(gymId, request.ids());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "İdman zalı analitikasını alın", description = "İdman zalı üçün ümumi gəlir, uğurlu/uğursuz girişlər və tarixçə siyahısını gətirir.")
    @PreAuthorize("hasAnyRole('ADMIN', 'GYM_SUPER_ADMIN', 'GYM_ADMIN')")
    @GetMapping("/v1/admin/gyms/{id}/analytics")
    public ResponseEntity<az.fitnest.catalog.dto.response.GymAnalyticsResponse> getGymAnalytics(
            @PathVariable("id") Long gymId,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime startDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime endDate,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ResponseEntity.ok(gymReadService.getGymAnalytics(gymId, startDate, endDate, status, sort, page, pageSize));
    }

    @Operation(summary = "İdman zalı analitika xülasəsini əl ilə yeniləyin", description = "İdman zalının ümumi gəlir, uğurlu və uğursuz giriş göstəricilərini əl ilə təyin edir (override). ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/v1/admin/gyms/{id}/analytics")
    public ResponseEntity<az.fitnest.catalog.dto.response.GymAnalyticsResponse> updateGymAnalytics(
            @PathVariable("id") Long gymId,
            @org.springframework.web.bind.annotation.RequestBody @jakarta.validation.Valid az.fitnest.catalog.dto.request.UpdateGymAnalyticsRequest request) {
        gymEntranceService.updateGymAnalyticsOverrides(gymId, request);
        return ResponseEntity.ok(gymReadService.getGymAnalytics(gymId, null, null, null, "date_desc", 1, 10));
    }

    @Operation(summary = "İdman zallarını siyahısını alın", description = "İdman zallarının adını, ünvanını, sahibini və statusunu qaytarır. Axtarış və sıralama dəstəklənir.")
    @PreAuthorize("hasAnyRole('ADMIN', 'GYM_SUPER_ADMIN', 'GYM_ADMIN')")
    @GetMapping("/v1/admin/gyms/list")
    public ResponseEntity<PaginatedResponse<az.fitnest.catalog.dto.response.AdminGymResponse>> getAllGyms(
            @io.swagger.v3.oas.annotations.Parameter(description = "Zal adı, ünvan və ya şəhər üzrə axtarış") @RequestParam(required = false) String query,
            @io.swagger.v3.oas.annotations.Parameter(description = "Sıralama qaydası. Dəyərlər: "
                    + "name_asc - Ad : A-Z, "
                    + "name_desc - Ad : Z-A, "
                    + "address_asc - Şəhər + Ünvan (A-Z), "
                    + "newest - Yeni əlavə edilmiş (son 1 həftə), "
                    + "deactivated - Deaktiv zallar", schema = @io.swagger.v3.oas.annotations.media.Schema(allowableValues = {
                    "name_asc", "name_desc", "address_asc", "newest", "deactivated"
            })) @RequestParam(required = false) String sort,
            @io.swagger.v3.oas.annotations.Parameter(description = "Səhifə nömrəsi (1-dən başlayır)", example = "1") @RequestParam(defaultValue = "1") int page,
            @io.swagger.v3.oas.annotations.Parameter(description = "Hər səhifədəki elementlərin sayı", example = "10") @RequestParam(defaultValue = "10") int pageSize) {
        return ResponseEntity.ok(gymReadService.getAllGymsAdmin(query, sort, page, pageSize));
    }

    @Operation(summary = "İstifadəçinin QR skan tarixçəsini alın", description = "Müəyyən bir istifadəçinin bütün QR skan cəhdlərinin (uğurlu/uğursuz) siyahısını, zal adlarını və platforma məlumatlarını gətirir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/v1/admin/gyms/users/{userId}/qr-history")
    public ResponseEntity<List<az.fitnest.catalog.dto.response.AdminQrScanHistoryResponse>> getUserQrScanHistory(
            @PathVariable Long userId,
            @io.swagger.v3.oas.annotations.Parameter(description = "Zal adı üzrə axtarış") @RequestParam(required = false) String query,
            @io.swagger.v3.oas.annotations.Parameter(description = "Sıralama qaydası. Dəyərlər: "
                    + "gymName_asc - Zal adı : A-Z, "
                    + "gymName_desc - Zal adı : Z-A, "
                    + "date_asc - Tarix : Köhnə -> Yeni, "
                    + "date_desc - Tarix : Yeni -> Köhnə, "
                    + "status_asc - Status : A-Z, "
                    + "status_desc - Status : Z-A, "
                    + "platform_asc - Platforma : A-Z, "
                    + "platform_desc - Platforma : Z-A", schema = @io.swagger.v3.oas.annotations.media.Schema(allowableValues = {
                    "gymName_asc", "gymName_desc", "date_asc", "date_desc",
                    "status_asc", "status_desc", "platform_asc", "platform_desc"
            })) @RequestParam(required = false) String sort) {
        return ResponseEntity.ok(gymReadService.getUserQrScanHistoryAdmin(userId, query, sort));
    }

    @Operation(summary = "Tam idman zalı yaradın (Birdəfəlik)", description = "Bütün 7 addımı birləşdirərək idman zalını bir dəfəyə yaradır. Daha sürətli və atomik əməliyyat.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/v1/admin/gyms/create-complete", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<GymCreateStep1Response> createGymComplete(
            @RequestPart("data") @Valid GymCreateCompleteRequest request,
            @RequestPart(value = "coverPhoto", required = false) MultipartFile coverPhoto,
            @RequestPart(value = "trainerPhotos", required = false) List<MultipartFile> trainerPhotos,
            @RequestPart(value = "roomPhotos", required = false) List<MultipartFile> roomPhotos,
            @RequestPart(value = "serviceIcons", required = false) List<MultipartFile> serviceIcons) {
        Long gymId = gymWriteService.createGymComplete(request, coverPhoto, trainerPhotos, roomPhotos, serviceIcons);
        return ResponseEntity.status(HttpStatus.CREATED).body(new GymCreateStep1Response(gymId));
    }

    @Operation(summary = "Step 1: Yeni idman zalı yaradın (DRAFT)", description = "Sistemə yeni idman zalı layihəsini əlavə edir.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/v1/admin/gyms/step1")
    public ResponseEntity<az.fitnest.catalog.dto.response.GymCreateStep1Response> createGymStep1(@Valid @RequestBody az.fitnest.catalog.dto.request.GymCreateStep1Request request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(gymWriteService.createGymStep1(request));
    }

    @Operation(summary = "Step 2: İdman zalına təlimçilər əlavə edin", description = "İdman zalına çoxlu təlimçi əlavə edir.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/v1/admin/gyms/{id}/step2", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> createGymStep2(
            @PathVariable Long id,
            @RequestParam(value = "names", required = false) List<String> names,
            @RequestParam(value = "surnames", required = false) List<String> surnames,
            @RequestParam(value = "professionIds", required = false) List<Long> professionIds,
            @RequestParam(value = "emails", required = false) List<String> emails,
            @RequestParam(value = "phones", required = false) List<String> phones,
            @RequestParam(value = "photos", required = false) List<MultipartFile> photos,
            @RequestParam(value = "lessonTypesPerTrainer", required = false) List<String> lessonTypesPerTrainer) {
        gymWriteService.createGymStep2(id, names, surnames, professionIds, emails, phones, photos, lessonTypesPerTrainer);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Step 3: İdman zalının iş saatlarını qeyd edin", description = "İdman zalının iş saatlarını qeyd edir.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/v1/admin/gyms/{id}/step3")
    public ResponseEntity<Void> createGymStep3(@PathVariable Long id, @Valid @RequestBody az.fitnest.catalog.dto.request.GymCreateStep2Request request) {
        gymWriteService.createGymStep3(id, request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Step 4: İdman zalının koordinatlarını qeyd edin", description = "İdman zalının ünvanını və koordinatlarını qeyd edir.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/v1/admin/gyms/{id}/step4")
    public ResponseEntity<Void> createGymStep4(@PathVariable Long id, @Valid @RequestBody az.fitnest.catalog.dto.request.GymCreateStep3Request request) {
        gymWriteService.createGymStep4(id, request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Step 5: İdman zalına şəkillər əlavə edin", description = "İdman zalı üçün üz qabığı və otaq şəkilləri yükləyir.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/v1/admin/gyms/{id}/step5", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> createGymStep5(
            @PathVariable Long id,
            @RequestParam("coverPhoto") MultipartFile coverPhoto,
            @RequestParam(value = "roomNames", required = false) java.util.List<String> roomNames,
            @RequestParam(value = "roomPhotos", required = false) java.util.List<MultipartFile> roomPhotos) {
        gymWriteService.createGymStep5(id, coverPhoto, roomNames, roomPhotos);
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "Step 6: İdman zalı üçün abunəlik və xidmətləri aktivləşdirin",
            description = "İdman zalı üçün abunəlikləri və dəstəklənən xidmətləri əlavə edir. " +
                    "Xidmət ikonları varsa multipart şəklində göndərilir."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/v1/admin/gyms/{id}/step6", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> createGymStep6(
            @PathVariable Long id,

            @io.swagger.v3.oas.annotations.Parameter(
                    description = "Abunəlik və xidmət məlumatları (JSON)",
                    required = true
            )
            @RequestPart("data") @Valid GymCreateStep6Request request,

            @io.swagger.v3.oas.annotations.Parameter(
                    description = "Xidmət ikonları (məcburi deyil, sıra customServices sırası ilə uyğun olmalıdır)"
            )
            @RequestPart(value = "serviceIcons", required = false) List<MultipartFile> serviceIcons) {

        gymWriteService.createGymStep6(id, request, serviceIcons);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Step 7: İdman zalı üçün admin yaradın və aktivləşdirin", description = "İdman zalı üçün admin yaradır və idman zalını ACTIVE edir.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/v1/admin/gyms/{id}/step7")
    public ResponseEntity<Void> createGymStep7(@PathVariable Long id, @Valid @RequestBody az.fitnest.catalog.dto.request.GymCreateStep7Request request) {
        gymWriteService.createGymStep7(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Operation(summary = "Validate Step 1", description = "Zal məlumatlarını yoxlayır.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/v1/admin/gyms/validate/step1")
    public ResponseEntity<Void> validateStep1(@Valid @RequestBody az.fitnest.catalog.dto.request.GymCreateStep1Request request) {
        gymWriteService.validateStep1(request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Validate Step 2", description = "Məşqçi məlumatlarını yoxlayır.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/v1/admin/gyms/validate/step2", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> validateStep2(
            @RequestParam("names") List<String> names,
            @RequestParam("surnames") List<String> surnames,
            @RequestParam(value = "professionIds", required = false) List<Long> professionIds,
            @RequestParam("emails") List<String> emails,
            @RequestParam("phones") List<String> phones,
            @RequestParam(value = "photos", required = false) List<MultipartFile> photos,
            @RequestParam("lessonTypesPerTrainer") List<String> lessonTypesPerTrainer) {
        gymWriteService.validateStep2(emails, phones);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Validate Step 3", description = "İş saatlarını yoxlayır.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/v1/admin/gyms/validate/step3")
    public ResponseEntity<Void> validateStep3(@Valid @RequestBody az.fitnest.catalog.dto.request.GymCreateStep2Request request) {
        gymWriteService.validateStep3(request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Validate Step 4", description = "Ünvan məlumatlarını yoxlayır.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/v1/admin/gyms/validate/step4")
    public ResponseEntity<Void> validateStep4(@Valid @RequestBody az.fitnest.catalog.dto.request.GymCreateStep3Request request) {
        gymWriteService.validateStep4(request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Validate Step 5", description = "Şəkil məlumatlarını yoxlayır.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/v1/admin/gyms/validate/step5", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> validateStep5(
            @RequestParam(value = "coverPhoto", required = false) MultipartFile coverPhoto,
            @RequestParam(value = "roomNames", required = false) List<String> roomNames,
            @RequestParam(value = "roomPhotos", required = false) List<MultipartFile> roomPhotos) {
        gymWriteService.validateStep5(coverPhoto, roomNames, roomPhotos);
        return ResponseEntity.ok().build();
    }

    @Operation(
            summary = "Validate Step 6",
            description = "Abunəlik və xidmət məlumatlarını, həmçinin xidmət ikonlarını yoxlayır."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/v1/admin/gyms/validate/step6", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> validateStep6(
            @io.swagger.v3.oas.annotations.Parameter(
                    description = "Abunəlik və xidmət məlumatları (JSON)",
                    required = true
            )
            @RequestPart("data") @Valid GymCreateStep6Request request,

            @io.swagger.v3.oas.annotations.Parameter(
                    description = "Xidmət ikonları (məcburi deyil)"
            )
            @RequestPart(value = "serviceIcons", required = false) List<MultipartFile> serviceIcons) {

        gymWriteService.validateStep6(request, serviceIcons);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Validate Step 7", description = "Admin məlumatlarını yoxlayır.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/v1/admin/gyms/validate/step7")
    public ResponseEntity<Void> validateStep7(@Valid @RequestBody az.fitnest.catalog.dto.request.GymCreateStep7Request request) {
        gymWriteService.validateStep7(request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "İdman zalı abunəliklərini yeniləyin", description = "İdman zalına aid abunəlik paketlərini və onlara daxil olan xidmətləri yeniləyir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/v1/admin/gyms/{id}/subscriptions")
    public ResponseEntity<Void> updateGymSubscriptions(@PathVariable Long id, @Valid @RequestBody az.fitnest.catalog.dto.request.GymCreateStep6Request request) {
        gymWriteService.updateGymSubscriptions(id, request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Dəstəklənən xidmət əlavə edin", description = "Sistemə yeni dəstəklənən xidmət əlavə edir.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/v1/admin/gyms/services", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<az.fitnest.catalog.dto.response.SupportedServiceResponse> createSupportedService(
            @RequestPart("data") @Valid az.fitnest.catalog.dto.request.SupportedServiceRequest request,
            @RequestPart(value = "icon", required = false) MultipartFile icon) {
        return ResponseEntity.status(HttpStatus.CREATED).body(gymWriteService.createSupportedService(request, icon));
    }

    @Operation(summary = "Dəstəklənən xidməti yeniləyin", description = "Mövcud xidmətin adını və/və ya ikonunu yeniləyir.")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping(value = "/v1/admin/gyms/services/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<az.fitnest.catalog.dto.response.SupportedServiceResponse> updateSupportedService(
            @PathVariable Long id,
            @RequestPart("data") @Valid az.fitnest.catalog.dto.request.SupportedServiceRequest request,
            @RequestPart(value = "icon", required = false) MultipartFile icon) {
        return ResponseEntity.ok(gymWriteService.updateSupportedService(id, request, icon));
    }

    @Operation(summary = "Dəstəklənən xidmətləri siyahılayın", description = "Sistemdəki dəstəklənən xidmətləri qaytarır. gymId göndərilərsə həmin idman zalına aid xidmətləri qaytarır.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/v1/admin/gyms/services")
    public ResponseEntity<java.util.List<az.fitnest.catalog.dto.response.SupportedServiceResponse>> getSupportedServices(@RequestParam(required = false) Long gymId) {
        return ResponseEntity.ok(gymReadService.getAllSupportedServices(gymId));
    }

    @Operation(summary = "Dəstəklənən xidməti silin", description = "ID-yə görə dəstəklənən xidməti silir.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/v1/admin/gyms/services/{id}")
    public ResponseEntity<Void> deleteSupportedService(@PathVariable Long id) {
        gymWriteService.deleteSupportedService(id);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Koordinatlara görə ünvanı alın", description = "Verilmiş enlik (latitude) və uzunluq (longitude) koordinatlarına uyğun ünvanı qaytarır.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/v1/admin/gyms/geocoding/reverse")
    public ResponseEntity<GeocodingResponse> reverseGeocode(@RequestParam Double lat, @RequestParam Double lng) {
        return ResponseEntity.ok(gymWriteService.reverseGeocode(lat, lng));
    }

    @Operation(summary = "Mətnə görə ünvan və koordinat axtarışı", description = "Verilmiş sorğu mətninə uyğun ünvan siyahısını və koordinatları qaytarır.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/v1/admin/gyms/geocoding/forward")
    public ResponseEntity<java.util.List<GeocodingResponse>> forwardGeocode(@RequestParam String query) {
        return ResponseEntity.ok(gymWriteService.forwardGeocode(query));
    }

    @Operation(summary = "İdman zalını aktivləşdirin və ya deaktiv edin", description = "İdman zalının statusunu ACTIVE və ya INACTIVE olaraq dəyişir.")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/v1/admin/gyms/{id}/status")
    public ResponseEntity<Void> toggleGymStatus(@PathVariable Long id, @RequestParam boolean enabled) {
        gymWriteService.toggleGymStatus(id, enabled);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "İdman zalı adminlərini alın", description = "İdman zalını idarə edən adminlərin siyahısını gətirir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/v1/admin/gyms/{id}/admins")
    public ResponseEntity<List<az.fitnest.catalog.dto.response.GymAdminResponse>> getGymAdmins(@PathVariable("id") Long gymId) {
        return ResponseEntity.ok(gymReadService.getGymAdmins(gymId));
    }

    @Operation(summary = "İdman zalına yeni admin əlavə edin", description = "İdman zalına yeni admin əlavə edir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/v1/admin/gyms/{id}/admins")
    public ResponseEntity<Void> addGymAdmin(@PathVariable("id") Long gymId, @Valid @RequestBody az.fitnest.catalog.dto.request.GymAdminCreateRequest request) {
        gymWriteService.addGymAdmin(gymId, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Operation(summary = "İdman zalı adminini yeniləyin", description = "İdman zalına aid admin məlumatlarını yeniləyir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/v1/admin/gyms/{id}/admins/{adminId}")
    public ResponseEntity<Void> updateGymAdmin(@PathVariable("id") Long gymId, @PathVariable("adminId") Long adminId, @Valid @RequestBody az.fitnest.catalog.dto.request.GymAdminUpdateRequest request) {
        gymWriteService.updateGymAdmin(gymId, adminId, request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "İdman zalı adminini silin", description = "İdman zalına aid admini silir. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/v1/admin/gyms/{id}/admins/{adminId}")
    public ResponseEntity<Void> deleteGymAdmin(@PathVariable("id") Long gymId, @PathVariable("adminId") Long adminId) {
        gymWriteService.deleteGymAdmin(gymId, adminId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "İdman zalı rezervasiyalarını alın", description = "İdman zalına aid rezervasiyaların siyahısını gətirir. ADMIN və ya idman zalı admini rolu tələb olunur.")
    @PreAuthorize("hasAnyRole('ADMIN', 'GYM_SUPER_ADMIN', 'GYM_ADMIN')")
    @GetMapping("/v1/admin/gyms/{id}/reservations")
    public ResponseEntity<PaginatedResponse<az.fitnest.catalog.dto.response.ReservationAdminResponse>> getGymReservations(
            @PathVariable Long id,
            @RequestParam(required = false) az.fitnest.catalog.model.enums.ReservationStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ResponseEntity.ok(gymReadService.getGymReservationsAdmin(id, status, page, pageSize));
    }

    @Operation(summary = "Rezervasiya detallarını alın", description = "ID-yə görə rezervasiyanın detallı məlumatlarını gətirir. ADMIN və ya idman zalı admini rolu tələb olunur.")
    @PreAuthorize("hasAnyRole('ADMIN', 'GYM_SUPER_ADMIN', 'GYM_ADMIN')")
    @GetMapping("/v1/admin/gyms/reservations/{reservationId}")
    public ResponseEntity<az.fitnest.catalog.dto.response.ReservationDetailAdminResponse> getReservationDetail(@PathVariable Long reservationId) {
        return ResponseEntity.ok(gymReadService.getReservationDetailAdmin(reservationId));
    }

    @Operation(summary = "Rezervasiya statusunu yeniləyin", description = "Rezervasiyanın statusunu təsdiqləyir və ya imtina edir. ADMIN və ya idman zalı admini rolu tələb olunur.")
    @PreAuthorize("hasAnyRole('ADMIN', 'GYM_SUPER_ADMIN', 'GYM_ADMIN')")
    @RequestMapping(value = "/v1/admin/gyms/reservations/{reservationId}/status", method = {org.springframework.web.bind.annotation.RequestMethod.POST, org.springframework.web.bind.annotation.RequestMethod.PATCH, org.springframework.web.bind.annotation.RequestMethod.PUT})
    public ResponseEntity<Void> updateReservationStatus(
            @PathVariable Long reservationId,
            @RequestParam az.fitnest.catalog.model.enums.ReservationStatus status,
            @RequestParam(required = false) String reason) {
        gymWriteService.updateReservationStatusAdmin(reservationId, status, reason);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "İdman zalı rezervasiya statistikasını alın", description = "İdman zalına aid rezervasiya statistikasını (ümumi, gözləmədə və s.) gətirir. ADMIN və ya idman zalı admini rolu tələb olunur.")
    @PreAuthorize("hasAnyRole('ADMIN', 'GYM_SUPER_ADMIN', 'GYM_ADMIN')")
    @GetMapping("/v1/admin/gyms/{id}/reservations/stats")
    public ResponseEntity<az.fitnest.catalog.dto.response.ReservationStatsResponse> getGymReservationStats(@PathVariable Long id) {
        return ResponseEntity.ok(gymReadService.getGymReservationStats(id));
    }

    @Operation(summary = "Dərs saatlarını alın", description = "İdman zalına aid dərs saatlarının siyahısını gətirir. ADMIN, GYM_SUPER_ADMIN və ya GYM_ADMIN rolu tələb olunur.")
    @PreAuthorize("hasAnyRole('ADMIN', 'GYM_SUPER_ADMIN', 'GYM_ADMIN')")
    @GetMapping("/v1/admin/gyms/{id}/lesson-hours")
    public ResponseEntity<PaginatedResponse<az.fitnest.catalog.dto.response.LessonHourResponse>> getGymLessonHours(
            @PathVariable Long id,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate startDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ResponseEntity.ok(gymReadService.getGymLessonHoursAdmin(id, startDate, endDate, page, pageSize));
    }

    @Operation(summary = "Yeni dərs saatı əlavə edin", description = "İdman zalına yeni dərs saatı əlavə edir. ADMIN, GYM_SUPER_ADMIN və ya GYM_ADMIN rolu tələb olunur.")
    @PreAuthorize("hasAnyRole('ADMIN', 'GYM_SUPER_ADMIN', 'GYM_ADMIN')")
    @PostMapping("/v1/admin/gyms/{id}/lesson-hours")
    public ResponseEntity<Void> addLessonHour(@PathVariable Long id, @jakarta.validation.Valid @RequestBody az.fitnest.catalog.dto.request.LessonHourRequest request) {
        gymWriteService.addLessonHourAdmin(id, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Operation(summary = "Dərs saatını silin", description = "ID-yə görə dərs saatını silir. ADMIN, GYM_SUPER_ADMIN və ya GYM_ADMIN rolu tələb olunur.")
    @PreAuthorize("hasAnyRole('ADMIN', 'GYM_SUPER_ADMIN', 'GYM_ADMIN')")
    @DeleteMapping("/v1/admin/gyms/lesson-hours/{lessonHourId}")
    public ResponseEntity<Void> deleteLessonHour(@PathVariable Long lessonHourId) {
        gymWriteService.deleteLessonHourAdmin(lessonHourId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Dərs saatına aid rezervasiya saylarını alın", description = "Dərs saatına aid təsdiq olunmuş və gözləmədə olan rezervasiya saylarını qaytarır.")
    @PreAuthorize("hasAnyRole('ADMIN', 'GYM_SUPER_ADMIN', 'GYM_ADMIN')")
    @GetMapping("/v1/admin/gyms/lesson-hours/{lessonHourId}/reservation-counts")
    public ResponseEntity<az.fitnest.catalog.dto.response.LessonHourReservationCountsResponse> getLessonHourReservationCounts(@PathVariable Long lessonHourId) {
        return ResponseEntity.ok(gymReadService.getLessonHourReservationCounts(lessonHourId));
    }

    // --- V2 Endpoints ---

@Operation(summary = "Tam idman zalı yaradın (V2, Birdəfəlik)", description = "Bütün 7 addımı birləşdirərək idman zalını V2 ilə bir dəfəyə yaradır.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/v2/admin/gyms/create-complete", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<GymCreateStep1Response> createGymCompleteV2(
            @RequestPart("data") @Valid GymCreateCompleteRequestV2 request,
            @RequestPart(value = "coverPhoto", required = false) MultipartFile coverPhoto,
            @RequestPart(value = "categoryCovers", required = false) List<MultipartFile> categoryCovers,
            @RequestParam(value = "categoryCoverCategoryIds", required = false) List<Long> categoryCoverCategoryIds,
            @RequestPart(value = "trainerPhotos", required = false) List<MultipartFile> trainerPhotos,
            @RequestPart(value = "roomPhotos", required = false) List<MultipartFile> roomPhotos,
            @RequestPart(value = "serviceIcons", required = false) List<MultipartFile> serviceIcons) {
        Long gymId = gymWriteService.createGymCompleteV2(request, coverPhoto, categoryCovers, categoryCoverCategoryIds, trainerPhotos, roomPhotos, serviceIcons);
        return ResponseEntity.status(HttpStatus.CREATED).body(new GymCreateStep1Response(gymId));
    }

    @Operation(summary = "Step 1: Yeni idman zalı yaradın (V2, DRAFT)")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/v2/admin/gyms/step1")
    public ResponseEntity<GymCreateStep1Response> createGymStep1V2(@Valid @RequestBody GymCreateStep1RequestV2 request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(gymWriteService.createGymStep1V2(request));
    }

    @Operation(summary = "Validate Step 1 (V2)")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/v2/admin/gyms/validate/step1")
    public ResponseEntity<Void> validateStep1V2(@Valid @RequestBody GymCreateStep1RequestV2 request) {
        gymWriteService.validateStep1V2(request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Step 5: İdman zalına şəkillər əlavə edin (V2)")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/v2/admin/gyms/{id}/step5", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> createGymStep5V2(
            @PathVariable Long id,
            @RequestParam(value = "coverPhoto", required = false) MultipartFile coverPhoto,
            @RequestParam(value = "categoryCovers", required = false) List<MultipartFile> categoryCovers,
            @RequestParam(value = "categoryCoverCategoryIds", required = false) List<Long> categoryCoverCategoryIds,
            @RequestParam(value = "roomNames", required = false) List<String> roomNames,
            @RequestParam(value = "roomCategoryIds", required = false) List<Long> roomCategoryIds,
            @RequestParam(value = "roomPhotos", required = false) List<MultipartFile> roomPhotos) {
        gymWriteService.createGymStep5V2(id, coverPhoto, categoryCovers, categoryCoverCategoryIds, roomNames, roomCategoryIds, roomPhotos);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Validate Step 5 (V2)")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/v2/admin/gyms/validate/step5", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> validateStep5V2(
            @RequestParam(value = "coverPhoto", required = false) MultipartFile coverPhoto,
            @RequestParam(value = "categoryCovers", required = false) List<MultipartFile> categoryCovers,
            @RequestParam(value = "categoryCoverCategoryIds", required = false) List<Long> categoryCoverCategoryIds,
            @RequestParam(value = "roomNames", required = false) List<String> roomNames,
            @RequestParam(value = "roomCategoryIds", required = false) List<Long> roomCategoryIds,
            @RequestParam(value = "roomPhotos", required = false) List<MultipartFile> roomPhotos) {
        gymWriteService.validateStep5V2(coverPhoto, categoryCovers, categoryCoverCategoryIds, roomNames, roomCategoryIds, roomPhotos);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Step 6: İdman zalı üçün abunəlik və xidmətləri aktivləşdirin (V2)")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/v2/admin/gyms/{id}/step6", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> createGymStep6V2(
            @PathVariable Long id,
            @RequestPart("data") @Valid GymCreateStep6RequestV2 request,
            @RequestPart(value = "serviceIcons", required = false) List<MultipartFile> serviceIcons) {
        gymWriteService.createGymStep6V2(id, request, serviceIcons);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Validate Step 6 (V2)")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/v2/admin/gyms/validate/step6", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> validateStep6V2(
            @RequestPart("data") @Valid GymCreateStep6RequestV2 request,
            @RequestPart(value = "serviceIcons", required = false) List<MultipartFile> serviceIcons) {
        gymWriteService.validateStep6V2(request, serviceIcons);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Zal abunəliklərini alın (V2)")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/v2/admin/gyms/{id}/subscriptions")
    public ResponseEntity<GymSubscriptionsAdminResponseV2> getGymSubscriptionsV2(@PathVariable("id") Long gymId) {
        return ResponseEntity.ok(gymReadService.getGymSubscriptionsV2(gymId));
    }

    @Operation(summary = "İdman zalı abunəliklərini yeniləyin (V2)")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/v2/admin/gyms/{id}/subscriptions")
    public ResponseEntity<Void> updateGymSubscriptionsV2(@PathVariable Long id, @Valid @RequestBody GymCreateStep6RequestV2 request) {
        gymWriteService.updateGymSubscriptionsV2(id, request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Zal məlumatlarını alın (V2)")
    @PreAuthorize("hasAnyRole('ADMIN', 'GYM_SUPER_ADMIN', 'GYM_ADMIN')")
    @GetMapping("/v2/admin/gyms/{id}/details")
    public ResponseEntity<GymInfoAdminResponseV2> getGymDetailsV2(@PathVariable("id") Long gymId) {
        return ResponseEntity.ok(gymReadService.getGymDetailsAdminV2(gymId));
    }

    @Operation(summary = "Zal məlumatlarını yeniləyin (V2)")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/v2/admin/gyms/{id}/details")
    public ResponseEntity<Void> updateGymDetailsV2(@PathVariable("id") Long gymId, @Valid @RequestBody GymInfoUpdateRequestV2 request) {
        gymWriteService.updateGymInfoV2(gymId, request);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "İdman zalı məşqçilərini əldə edin (V2)", description = "İdman zalında çalışan məşqçilərin siyahısını kateqoriya ID-ləri ilə birlikdə qaytarır. ADMIN rolu tələb olunur.")
    @PreAuthorize("hasAnyRole('ADMIN', 'GYM_SUPER_ADMIN', 'GYM_ADMIN')")
    @GetMapping("/v2/admin/gyms/{id}/trainers")
    public ResponseEntity<PaginatedResponse<GymTrainerResponseV2>> getTrainersV2(
            @PathVariable("id") Long gymId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(value = "sort_dir", defaultValue = "DESC") az.fitnest.catalog.dto.SortDirection sortDir) {
        int safePageSize = Math.min(pageSize, 100);
        return ResponseEntity.ok(gymTrainerService.getTrainersV2(gymId, page, safePageSize, sortDir.name().toLowerCase()));
    }
}