package az.fitnest.catalog.controller;

import az.fitnest.catalog.dto.PaginatedResponse;
import az.fitnest.catalog.dto.SortDirection;
import az.fitnest.catalog.dto.request.ReviewRequest;
import az.fitnest.catalog.dto.response.GymCategoryCountResponse;
import az.fitnest.catalog.dto.response.GymCountResponse;
import az.fitnest.catalog.dto.response.GymDetailResponse;
import az.fitnest.catalog.dto.response.GymDetailResponseV2;
import az.fitnest.catalog.dto.response.GymDetailResponseV3;
import az.fitnest.catalog.dto.response.GymEntranceEligibilityResponse;
import az.fitnest.catalog.dto.response.GymEntranceScanResponse;
import az.fitnest.catalog.dto.response.GymImageResponse;
import az.fitnest.catalog.dto.response.GymMainPageResponse;
import az.fitnest.catalog.dto.response.GymMainPageResponseV2;
import az.fitnest.catalog.dto.response.GymQrResponse;
import az.fitnest.catalog.dto.response.GymReviewResponse;
import az.fitnest.catalog.dto.response.GymSubscriptionCountResponse;
import az.fitnest.catalog.dto.response.GymTrainerResponse;
import az.fitnest.catalog.dto.response.GymTypeCountResponse;
import az.fitnest.catalog.dto.response.LocationResponse;
import az.fitnest.catalog.service.GymImageService;
import az.fitnest.catalog.service.GymReadService;
import az.fitnest.catalog.service.GymReviewService;
import az.fitnest.catalog.service.GymTrainerService;
import az.fitnest.catalog.service.GymWriteService;
import az.fitnest.catalog.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Gyms", description = "İdman zallarını idarə etmək və kəşf etmək üçün ucluqlar, o cümlədən paketlər, məşqçilər və istifadəçi rəyləri")
public class GymController {
    private final GymReadService gymReadService;
    private final GymWriteService gymWriteService;
    private final GymImageService gymImageService;
    private final GymReviewService gymReviewService;
    private final GymTrainerService gymTrainerService;

    @GetMapping("/v1/gyms/{gymId:\\d+}")
    @Operation(summary = "İdman zalı təfərrüatlarını əldə edin", description = "Xüsusi bir idman zalının tam təfərrüatlarını, o cümlədən yerləşmə yeri, obyektləri və istifadəçiyə xüsusi favorit statusunu əldə edir.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "İdman zalı təfərrüatları uğurla əldə edildi", content = {@Content(schema = @Schema(implementation = GymDetailResponse.class))}), @ApiResponse(responseCode = "404", description = "İdman zalı tapılmadı")})
    public ResponseEntity<GymDetailResponse> getGymDetail(@AuthenticationPrincipal Object principal, @Parameter(description = "İdman zalının ID-si") @PathVariable Long gymId) {
        Long userId = UserContext.extractUserId(principal);
        return ResponseEntity.ok(this.gymReadService.getGymDetail(userId, gymId));
    }

    @GetMapping("/v1/gyms/{gymId:\\d+}/images")
    @Operation(summary = "İdman zalı şəkillərini əldə edin", description = "İdman zalı ilə əlaqəli bütün şəkillərin (loqo, üz qabığı, interyer) siyahısını qaytarır.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Şəkillər uğurla əldə edildi", content = {@Content(schema = @Schema(implementation = GymImageResponse.class))})})
    public ResponseEntity<GymImageResponse> getGymImages(@PathVariable Long gymId) {
        return ResponseEntity.ok(this.gymReadService.getGymImages(gymId));
    }

    @GetMapping("/v1/gyms/{gymId:\\d+}/qr")
    @Operation(summary = "İdman zalı QR kod URL-ni əldə edin", description = "İdman zalının QR kod şəkli üçün yayım URL-ni qaytarır.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "QR kod URL-i uğurla əldə edildi", content = {@Content(schema = @Schema(implementation = GymQrResponse.class))})})
    public ResponseEntity<GymQrResponse> getGymQrUrl(@Parameter(description = "İdman zalının ID-si") @PathVariable Long gymId) {
        String qrCodeUrl = this.gymReadService.getGymQrUrl(gymId);
        return ResponseEntity.ok(new GymQrResponse(qrCodeUrl));
    }

    @GetMapping("/v1/gyms/{gymId:\\d+}/trainers")
    @Operation(summary = "İdman zalı məşqçilərini əldə edin", description = "İdman zalında çalışan məşqçilərin səhifələnmiş siyahısını qaytarır.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Məşqçilər uğurla əldə edildi")})
    public ResponseEntity<PaginatedResponse<GymTrainerResponse>> getTrainers(
            @PathVariable Long gymId,
            @Parameter(description = "Səhifə indeksi (1-dən başlayaraq)") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Hər səhifədəki elementlərin sayı") @RequestParam(defaultValue = "10") int page_size,
            @Parameter(description = "Sıralama istiqaməti. ASC (Artan sıra, A-dan Z-yə, ən aşağıdan ən yuxarıya), DESC (Azalan sıra, Z-dən A-ya, ən yuxarıdan ən aşağıya", schema = @Schema(implementation = SortDirection.class, example = "DESC")) @RequestParam(value = "sort_dir", defaultValue = "DESC") SortDirection sortDir) {
        int safePageSize = Math.min(page_size, 100);
        return ResponseEntity.ok(this.gymTrainerService.getTrainers(gymId, page, safePageSize, sortDir.name().toLowerCase()));
    }

    @GetMapping("/v1/gyms/{gymId:\\d+}/reviews")
    @Operation(summary = "İdman zalı rəylərini əldə edin", description = "İdman zalı üçün istifadəçi rəylərinin səhifələnmiş siyahısını qaytarır.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Rəylər uğurla əldə edildi")})
    public ResponseEntity<PaginatedResponse<GymReviewResponse>> getReviews(@PathVariable Long gymId, @Parameter(description = "Səhifə indeksi (1-dən başlayaraq)") @RequestParam(defaultValue = "1") int page, @Parameter(description = "Hər səhifədəki elementlərin sayı") @RequestParam(defaultValue = "10") int page_size, @Parameter(description = "Çeşidləmə qaydası (məsələn, ən yeni, ən yüksək reytinq)") @RequestParam(required = false) String sort) {
        int safePageSize = Math.min(page_size, 100);
        return ResponseEntity.ok(this.gymReviewService.getReviews(gymId, page, safePageSize, sort));
    }

    @PostMapping("/v1/gyms/{gymId:\\d+}/reviews")
    @Operation(summary = "Rəy əlavə edin", description = "İdman zalı üçün yeni istifadəçi rəyi və reytinqi əlavə edir. Reytinq 1-5 arasında olmalıdır.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {@ApiResponse(responseCode = "201", description = "Rəy uğurla əlavə edildi"), @ApiResponse(responseCode = "401", description = "İstifadəçi autentifikasiya olunmayıb")})
    public ResponseEntity<Void> addReview(@AuthenticationPrincipal Object principal, @PathVariable Long gymId, @Valid @RequestBody ReviewRequest request) {
        Long userId = UserContext.extractUserId(principal);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        this.gymReviewService.addReview(userId, gymId, request);
        return ResponseEntity.status(201).build();
    }

    @GetMapping("/v1/gyms")
    @Operation(summary = "İdman zallarını əldə edin", description = "Bütün idman zalı siyahıları üçün birləşdirilmiş ucluq (Hamısı, Ən Yaxın, Yeni, Saxlanılanlar). Görünüşləri dəyişmək üçün 'type' parametrindən istifadə edin.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "İdman zalları uğurla əldə edildi", content = {@Content(schema = @Schema(implementation = PaginatedResponse.class))}), @ApiResponse(responseCode = "401", description = "İstifadəçi autentifikasiya olunmayıb")})
    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PaginatedResponse<GymMainPageResponse>> getGyms(
            @AuthenticationPrincipal Object principal,
            @Parameter(description = "Axtarış sorğusu") @RequestParam(value = "q", required = false) String q,
            @Parameter(description = "Filtr növü (ALL, NEW, CLOSEST, SAVED)") @RequestParam(value = "type", defaultValue = "ALL") String type,
            @Parameter(description = "Kateqoriya ID-si") @RequestParam(value = "category", required = false) Long categoryId,
            @Parameter(description = "Abunəlik ID-si") @RequestParam(value = "subscriptionId", required = false) Long subscriptionId,
            @Parameter(description = "Səhifə indeksi (1-dən başlayaraq)") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Hər səhifədəki elementlərin sayı") @RequestParam(defaultValue = "10") int page_size,
            @Parameter(description = "İstifadəçinin enliyi (latitude)") @RequestParam(value = "lat", required = false) Double lat,
            @Parameter(description = "İstifadəçinin uzunluğu (longitude)") @RequestParam(value = "lng", required = false) Double lng,
            @Parameter(description = "Sıralama istiqaməti. ASC (Artan sıra, A-dan Z-yə, ən aşağıdan ən yuxarıya), DESC (Azalan sıra, Z-dən A-ya, ən yuxarıdan ən aşağıya", schema = @Schema(implementation = SortDirection.class, example = "DESC")) @RequestParam(value = "sort_dir", defaultValue = "DESC") SortDirection sortDir) {
        Long userId = UserContext.extractUserId(principal);
        int safePageSize = Math.min(page_size, 100);
        return ResponseEntity.ok(this.gymReadService.getGyms(userId, q, type, categoryId, subscriptionId, page, safePageSize, lat, lng, sortDir.name().toLowerCase()));
    }

    @PostMapping("/v1/gyms/{gymId:\\d+}/save")
    @Operation(summary = "İdman zalını saxla/sil", description = "Autentifikasiya olunmuş istifadəçi üçün idman zalının 'saxlanılanlar' statusunu dəyişir.")
    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("isAuthenticated()")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Dəyişiklik uğurludur", content = {@Content(schema = @Schema(implementation = az.fitnest.catalog.dto.response.ToggleSaveResponse.class))}),
            @ApiResponse(responseCode = "404", description = "İdman zalı tapılmadı", content = {@Content(schema = @Schema(implementation = az.fitnest.catalog.dto.response.ApiError.class))}),
            @ApiResponse(responseCode = "401", description = "İstifadəçi autentifikasiya olunmayıb", content = {@Content(schema = @Schema(implementation = az.fitnest.catalog.dto.response.ApiError.class))})
    })
    public ResponseEntity<az.fitnest.catalog.dto.response.ToggleSaveResponse> toggleSave(@org.springframework.security.core.annotation.AuthenticationPrincipal Object principal, @PathVariable Long gymId) {
        boolean isSaved = this.gymWriteService.toggleSave(principal, gymId);
        return ResponseEntity.ok(new az.fitnest.catalog.dto.response.ToggleSaveResponse(isSaved));
    }

    @GetMapping("/v1/gyms/{gymId:\\d+}/location")
    @Operation(summary = "İdman zalının yerləşdiyi yeri əldə edin", description = "İdman zalı üçün həll edilmiş ünvan mətni ilə birlikdə enlik və uzunluq koordinatlarını qaytarır.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Məkan uğurla əldə edildi", content = {@Content(schema = @Schema(implementation = LocationResponse.class))}), @ApiResponse(responseCode = "404", description = "İdman zalı tapılmadı")})
    public ResponseEntity<LocationResponse> getGymLocation(@Parameter(description = "İdman zalının ID-si") @PathVariable Long gymId) {
        return ResponseEntity.ok(this.gymReadService.getGymLocation(gymId));
    }

    @PostMapping("/v1/gyms/entrance/scan")
    @Operation(summary = "Scan gym QR code for entrance", description = "Checks proximity, working hours, and subscription eligibility. Performs automatic check-in if allowed.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Entrance scan result (includes isAllowed, status, and reason)", content = @Content(schema = @Schema(implementation = az.fitnest.catalog.dto.response.GymEntranceScanResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ResponseEntity<GymEntranceScanResponse> scanGymQrEntrance(
            @AuthenticationPrincipal Object principal,
            @RequestParam String qrCodeValue,
            @RequestParam Double lat,
            @RequestParam Double lng,
            @org.springframework.web.bind.annotation.RequestHeader(value = "User-Agent", required = false) String userAgent) {
        try {
            var response = gymReadService.scanGymQrEntrance(principal, qrCodeValue, lat, lng, userAgent);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).build();
        }
    }

    @PostMapping("/v1/gyms/entrance/eligibility")
    @Operation(summary = "Check gym entrance eligibility", description = "Checks entry limit and subscription status.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Eligibility check result (includes allowed status and reason)", content = @Content(schema = @Schema(implementation = GymEntranceEligibilityResponse.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ResponseEntity<GymEntranceEligibilityResponse> checkGymEntranceEligibility(
            @AuthenticationPrincipal Object principal) {
        var response = gymReadService.checkGymEntranceEligibility(principal);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/v1/gyms/count")
    @Operation(summary = "Get gym count", description = "Returns count of gyms filtered by type (all, new), subscriptionId, and categoryId.")
    public ResponseEntity<GymCountResponse> getGymCount(
            @RequestParam(value = "type", required = false, defaultValue = "all") String type,
            @RequestParam(value = "subscriptionId", required = false) Long subscriptionId,
            @RequestParam(value = "categoryId", required = false) Long categoryId) {
        GymCountResponse response = gymReadService.getGymCount(type, subscriptionId, categoryId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/v1/gyms/count/by-type")
    @Operation(summary = "Get gym count by type", description = "Returns count of gyms for the requested type (all, new).")
    public ResponseEntity<GymTypeCountResponse> getGymCountByType(@RequestParam(value = "type", required = false, defaultValue = "all") String type) {
        return ResponseEntity.ok(gymReadService.getGymCountByType(type));
    }

    @GetMapping("/v1/gyms/count/by-category")
    @Operation(summary = "Get gym count by category", description = "Returns all categories with their gym counts.")
    public ResponseEntity<List<GymCategoryCountResponse>> getGymCountByCategory() {
        return ResponseEntity.ok(gymReadService.getGymCountByCategory());
    }

    @GetMapping("/v1/gyms/count/by-subscription")
    @Operation(summary = "Get gym count by subscription", description = "Returns all subscriptions with their gym counts.")
    public ResponseEntity<List<GymSubscriptionCountResponse>> getGymCountBySubscription() {
        return ResponseEntity.ok(gymReadService.getGymCountBySubscription());
    }

    @GetMapping("/v1/gyms/count/by-gender")
    @Operation(summary = "Get gym count by gender", description = "Returns count of gyms with gender-specific work hours (male/female).")
    public ResponseEntity<GymTypeCountResponse> getGymCountByGender(@RequestParam("gender") String gender) {
        return ResponseEntity.ok(gymReadService.getGymCountByGender(gender));
    }

    // --- V2 Endpoints ---

@GetMapping("/v2/gyms/{gymId:\\d+}")
    @Operation(summary = "İdman zalı təfərrüatlarını əldə edin (V2)", description = "Xüsusi bir idman zalının tam təfərrüatlarını (çoxlu kateqoriya dəstəyi ilə) əldə edir.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "İdman zalı təfərrüatları uğurla əldə edildi", content = {@Content(schema = @Schema(implementation = GymDetailResponseV2.class))}),
            @ApiResponse(responseCode = "404", description = "İdman zalı tapılmadı")
    })
    public ResponseEntity<GymDetailResponseV2> getGymDetailV2(
            @AuthenticationPrincipal Object principal,
            @Parameter(description = "İdman zalının ID-si") @PathVariable Long gymId) {
        Long userId = UserContext.extractUserId(principal);
        return ResponseEntity.ok(this.gymReadService.getGymDetailV2(userId, gymId));
    }

    @GetMapping("/v3/gyms/{gymId:\\d+}")
    @Operation(
            summary = "İdman zalı About təfərrüatları (V3)",
            description = "Yalnız About/header üçün yüngül payload: açıq/bağlı, məsafə, loqo, başlanğıc qiymət və amenities. İş saatları, abunəliklər, rəylər, məşqçilər və qalereya ayrı ucluqlardan yüklənir."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "İdman zalı təfərrüatları uğurla əldə edildi", content = {@Content(schema = @Schema(implementation = GymDetailResponseV3.class))}),
            @ApiResponse(responseCode = "404", description = "İdman zalı tapılmadı")
    })
    public ResponseEntity<GymDetailResponseV3> getGymDetailV3(
            @AuthenticationPrincipal Object principal,
            @Parameter(description = "İdman zalının ID-si") @PathVariable Long gymId,
            @Parameter(description = "İstifadəçinin enliyi (latitude)") @RequestParam(value = "lat", required = false) Double lat,
            @Parameter(description = "İstifadəçinin uzunluğu (longitude)") @RequestParam(value = "lng", required = false) Double lng) {
        Long userId = UserContext.extractUserId(principal);
        return ResponseEntity.ok(this.gymReadService.getGymDetailV3(userId, gymId, lat, lng));
    }

    @GetMapping("/v2/gyms")
    @Operation(summary = "İdman zallarını əldə edin (V2)", description = "Çoxlu kateqoriya dəstəyi ilə idman zalları siyahısını qaytarır.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "İdman zalları uğurla əldə edildi", content = {@Content(schema = @Schema(implementation = PaginatedResponse.class))})
    })
    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PaginatedResponse<GymMainPageResponseV2>> getGymsV2(
            @AuthenticationPrincipal Object principal,
            @Parameter(description = "Axtarış sorğusu") @RequestParam(value = "q", required = false) String q,
            @Parameter(description = "Filtr növü (ALL, NEW, CLOSEST, SAVED)") @RequestParam(value = "type", defaultValue = "ALL") String type,
            @Parameter(description = "Kateqoriya ID-si") @RequestParam(value = "category", required = false) Long categoryId,
            @Parameter(description = "Abunəlik ID-si") @RequestParam(value = "subscriptionId", required = false) Long subscriptionId,
            @Parameter(description = "Səhifə indeksi (1-dən başlayaraq)") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Hər səhifədəki elementlərin sayı") @RequestParam(defaultValue = "10") int page_size,
            @Parameter(description = "İstifadəçinin enliyi (latitude)") @RequestParam(value = "lat", required = false) Double lat,
            @Parameter(description = "İstifadəçinin uzunluğu (longitude)") @RequestParam(value = "lng", required = false) Double lng,
            @Parameter(description = "Sıralama istiqaməti. ASC, DESC") @RequestParam(value = "sort_dir", defaultValue = "DESC") SortDirection sortDir) {
        Long userId = UserContext.extractUserId(principal);
        int safePageSize = Math.min(page_size, 100);
        return ResponseEntity.ok(this.gymReadService.getGymsV2(userId, q, type, categoryId, subscriptionId, page, safePageSize, lat, lng, sortDir.name().toLowerCase()));
    }

    @Operation(summary = "Arxiv dərs saatlarını alın", description = "İdman zalına aid keçmiş (son 1 həftə) dərs saatlarının siyahısını gətirir. ADMIN, GYM_SUPER_ADMIN və ya GYM_ADMIN rolu tələb olunur.")
    @PreAuthorize("hasAnyRole('ADMIN', 'GYM_SUPER_ADMIN', 'GYM_ADMIN')")
    @GetMapping("/v1/admin/gyms/{id}/lesson-hours/archive")
    public ResponseEntity<PaginatedResponse<az.fitnest.catalog.dto.response.LessonHourResponse>> getGymLessonHoursArchive(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ResponseEntity.ok(gymReadService.getGymLessonHoursArchiveAdmin(id, page, pageSize));
    }
}