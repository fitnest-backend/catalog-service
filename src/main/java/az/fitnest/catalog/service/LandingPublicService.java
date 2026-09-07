package az.fitnest.catalog.service;

import az.fitnest.catalog.dto.PaginatedResponse;
import az.fitnest.catalog.dto.response.LandingGymCardResponse;
import az.fitnest.catalog.dto.response.LandingGymDetailResponse;
import az.fitnest.catalog.dto.response.LandingStatsResponse;
import az.fitnest.catalog.dto.response.LandingStoreCardResponse;
import az.fitnest.catalog.dto.response.LandingStoreDetailResponse;

public interface LandingPublicService {
    LandingStatsResponse getStats();

    PaginatedResponse<LandingGymCardResponse> getHomeGyms();

    PaginatedResponse<LandingGymCardResponse> getGyms(int page, int pageSize);

    LandingGymDetailResponse getGym(Long gymId);

    PaginatedResponse<LandingStoreCardResponse> getHomeStores();

    PaginatedResponse<LandingStoreCardResponse> getStores(int page, int pageSize);

    LandingStoreDetailResponse getStore(Long storeId);

    boolean isPublicLandingMedia(String fileId);

    void streamPublicLandingMedia(String fileId, java.io.OutputStream outputStream);
}
