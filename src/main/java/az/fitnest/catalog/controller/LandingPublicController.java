package az.fitnest.catalog.controller;

import az.fitnest.catalog.dto.PaginatedResponse;
import az.fitnest.catalog.dto.response.LandingGymResponse;
import az.fitnest.catalog.dto.response.LandingStatsResponse;
import az.fitnest.catalog.dto.response.LandingStoreResponse;
import az.fitnest.catalog.service.LandingPublicService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/landing")
@RequiredArgsConstructor
@Tag(name = "Landing Public", description = "Unauthenticated landing-page catalog endpoints")
public class LandingPublicController {

    private final LandingPublicService landingPublicService;

    @Operation(summary = "Landing stats", description = "Returns live gym, platinum, visit, and package counts. Cached in Redis.")
    @GetMapping("/stats")
    public ResponseEntity<LandingStatsResponse> getStats() {
        return ResponseEntity.ok(landingPublicService.getStats());
    }

    @Operation(summary = "Landing gyms", description = "Returns active gyms for the public landing site. Cached in Redis.")
    @GetMapping("/gyms")
    public ResponseEntity<PaginatedResponse<LandingGymResponse>> getGyms(
            @Parameter(description = "Page index, starting at 1") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Items per page") @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return ResponseEntity.ok(landingPublicService.getGyms(page, pageSize));
    }

    @Operation(summary = "Landing gym detail", description = "Returns a single active gym. Cached in Redis.")
    @GetMapping("/gyms/{gymId:\\d+}")
    public ResponseEntity<LandingGymResponse> getGym(@PathVariable Long gymId) {
        return ResponseEntity.ok(landingPublicService.getGym(gymId));
    }

    @Operation(summary = "Landing stores", description = "Returns active FitStore partners for the public landing site. Cached in Redis.")
    @GetMapping("/stores")
    public ResponseEntity<PaginatedResponse<LandingStoreResponse>> getStores(
            @Parameter(description = "Page index, starting at 1") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Items per page") @RequestParam(value = "page_size", defaultValue = "10") int pageSize) {
        return ResponseEntity.ok(landingPublicService.getStores(page, pageSize));
    }

    @Operation(summary = "Landing store detail", description = "Returns a single active store. Cached in Redis.")
    @GetMapping("/stores/{storeId:\\d+}")
    public ResponseEntity<LandingStoreResponse> getStore(@PathVariable Long storeId) {
        return ResponseEntity.ok(landingPublicService.getStore(storeId));
    }

    @Operation(
            summary = "Public landing media",
            description = "Streams a gym or store cover that is already published on the landing site. Other media IDs return 404.")
    @GetMapping("/media/{fileId:[0-9]{1,32}}")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> streamMedia(
            @PathVariable String fileId) {
        if (!landingPublicService.isPublicLandingMedia(fileId)) {
            return ResponseEntity.notFound().build();
        }
        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body = outputStream ->
                landingPublicService.streamPublicLandingMedia(fileId, outputStream);
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.IMAGE_JPEG)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }
}
