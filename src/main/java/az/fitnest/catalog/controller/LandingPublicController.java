package az.fitnest.catalog.controller;

import az.fitnest.catalog.dto.PaginatedResponse;
import az.fitnest.catalog.dto.response.LandingGymCardResponse;
import az.fitnest.catalog.dto.response.LandingGymDetailResponse;
import az.fitnest.catalog.dto.response.LandingStatsResponse;
import az.fitnest.catalog.dto.response.LandingStoreCardResponse;
import az.fitnest.catalog.dto.response.LandingStoreDetailResponse;
import az.fitnest.catalog.service.LandingPublicService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/public/landing")
@RequiredArgsConstructor
@Tag(name = "Landing Public", description = "Unauthenticated landing-page catalog endpoints. Card and detail payloads are separate.")
public class LandingPublicController {

    private static final CacheControl JSON_CACHE = CacheControl
            .maxAge(Duration.ofMinutes(2))
            .cachePublic()
            .staleWhileRevalidate(Duration.ofMinutes(2));
    private static final CacheControl MEDIA_CACHE = CacheControl
            .maxAge(1, TimeUnit.HOURS)
            .cachePublic();

    private final LandingPublicService landingPublicService;

    @Operation(summary = "Landing stats", description = "Counts only. Cached in Redis.")
    @GetMapping("/stats")
    public ResponseEntity<LandingStatsResponse> getStats() {
        return cachedJson(landingPublicService.getStats());
    }

    @Operation(summary = "Homepage gyms", description = "Fixed 3 gym cards for the homepage. No detail fields.")
    @GetMapping("/home/gyms")
    public ResponseEntity<PaginatedResponse<LandingGymCardResponse>> getHomeGyms() {
        return cachedJson(landingPublicService.getHomeGyms());
    }

    @Operation(summary = "Gym listing cards", description = "Paginated gym cards for the fitness-centers page. page_size is capped.")
    @GetMapping("/gyms")
    public ResponseEntity<PaginatedResponse<LandingGymCardResponse>> getGyms(
            @Parameter(description = "Page index, starting at 1") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Items per page, max 50") @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return cachedJson(landingPublicService.getGyms(sanitizePage(page), sanitizePageSize(pageSize)));
    }

    @Operation(summary = "Gym detail", description = "Public gym profile: gallery, amenities, hours, map. No email, QR, or staff.")
    @GetMapping("/gyms/{gymId:\\d+}")
    public ResponseEntity<LandingGymDetailResponse> getGym(@PathVariable Long gymId) {
        return cachedJson(landingPublicService.getGym(gymId));
    }

    @Operation(summary = "Homepage stores", description = "Fixed 3 FitStore cards for the homepage. No email.")
    @GetMapping("/home/stores")
    public ResponseEntity<PaginatedResponse<LandingStoreCardResponse>> getHomeStores() {
        return cachedJson(landingPublicService.getHomeStores());
    }

    @Operation(summary = "Store listing cards", description = "Paginated FitStore cards. Email is omitted.")
    @GetMapping("/stores")
    public ResponseEntity<PaginatedResponse<LandingStoreCardResponse>> getStores(
            @Parameter(description = "Page index, starting at 1") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Items per page, max 50") @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return cachedJson(landingPublicService.getStores(sanitizePage(page), sanitizePageSize(pageSize)));
    }

    @Operation(summary = "Store detail", description = "Public FitStore profile.")
    @GetMapping("/stores/{storeId:\\d+}")
    public ResponseEntity<LandingStoreDetailResponse> getStore(@PathVariable Long storeId) {
        return cachedJson(landingPublicService.getStore(storeId));
    }

    @Operation(
            summary = "Public landing media",
            description = "Streams a cover or gallery file that is already published on an ACTIVE gym or store. Other IDs return 404.")
    @GetMapping("/media/{fileId:[1-9][0-9]{0,31}}")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> streamMedia(
            @PathVariable String fileId) {
        if (!landingPublicService.isPublicLandingMedia(fileId)) {
            return ResponseEntity.notFound().build();
        }
        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body = outputStream ->
                landingPublicService.streamPublicLandingMedia(fileId, outputStream);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(MEDIA_CACHE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    private int sanitizePage(int page) {
        return Math.min(Math.max(page, 1), 40);
    }

    private int sanitizePageSize(int pageSize) {
        return Math.min(Math.max(pageSize, 1), 50);
    }

    private <T> ResponseEntity<T> cachedJson(T body) {
        return ResponseEntity.ok()
                .cacheControl(JSON_CACHE)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.VARY, "Accept-Language")
                .body(body);
    }
}
