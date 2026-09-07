package az.fitnest.catalog.service;

import az.fitnest.catalog.dto.PaginatedResponse;
import az.fitnest.catalog.dto.response.LandingGymResponse;
import az.fitnest.catalog.dto.response.LandingStatsResponse;
import az.fitnest.catalog.dto.response.LandingStoreResponse;

public interface LandingPublicService {
    LandingStatsResponse getStats();

    PaginatedResponse<LandingGymResponse> getGyms(int page, int pageSize);

    LandingGymResponse getGym(Long gymId);

    PaginatedResponse<LandingStoreResponse> getStores(int page, int pageSize);

    LandingStoreResponse getStore(Long storeId);

    boolean isPublicLandingMedia(String fileId);

    void streamPublicLandingMedia(String fileId, java.io.OutputStream outputStream);
}
