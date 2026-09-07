package az.fitnest.catalog.service;

import az.fitnest.catalog.dto.*;
import az.fitnest.catalog.dto.request.*;
import az.fitnest.catalog.dto.response.*;
import java.util.List;
import java.util.Map;

public interface GymReadService {
    List<SupportedServiceResponse> getAllSupportedServices(Long gymId);
    String getUserLanguage(Long userId);
    GymDetailResponse getGymDetail(Long userId, Long gymId);
    GymImageResponse getGymImages(Long gymId);
    boolean isReservationEnabled(Long gymId);
    Map<String, Object> getReservationRules(Long gymId);
    List<GymNearbyResponse> getNearbyGyms(double lat, double lng, double radiusKm);
    PaginatedResponse<GymMainPageResponse> getClosestGyms(Long userId, int page, int pageSize, Double userLat, Double userLng);
    PaginatedResponse<GymMainPageResponse> getGyms(Long userId, String q, String type, Long categoryId, Long subscriptionId, int page, int pageSize, Double userLat, Double userLng, String sortDir);
    PaginatedResponse<AdminGymResponse> getAllGymsAdmin(String query, String sort, int page, int pageSize);
    List<AdminQrScanHistoryResponse> getUserQrScanHistoryAdmin(Long userId, String query, String sort);
    LocationResponse getGymLocation(Long gymId);
    boolean gymSupportsPlan(Long gymId, Long planId);
    GymEntranceResponse checkProximity(Double lat, Double lng, Long gymId);
    boolean checkGymEntranceEligibilitySimple(Object principal);
    GymCountResponse getGymCount(String type, Long subscriptionId, Long categoryId);
    GymTypeCountResponse getGymCountByType(String type);
    List<GymCategoryCountResponse> getGymCountByCategory();
    List<GymSubscriptionCountResponse> getGymCountBySubscription();
    double calculateDistanceRaw(double lat1, double lng1, double lat2, double lng2);
    String getGymQrUrl(Long gymId);
    GymEntranceScanResponse scanGymQrEntrance(Object principal, String qrCodeValue, Double lat, Double lng, String userAgent);
    List<GymEntranceHistoryAdminResponse> getGymEntranceHistory(Long gymId);
    az.fitnest.catalog.dto.response.GymAnalyticsResponse getGymAnalytics(Long gymId, java.time.LocalDateTime startDate, java.time.LocalDateTime endDate, String status, String sort, int page, int pageSize);
    az.fitnest.catalog.dto.response.GymInfoAdminResponse getGymDetailsAdmin(Long gymId);
    az.fitnest.catalog.dto.response.GymSubscriptionsAdminResponse getGymSubscriptions(Long gymId);
    GymEntranceEligibilityResponse checkGymEntranceEligibility(Object principal);
    GymTypeCountResponse getGymCountByGender(String gender);
    List<az.fitnest.catalog.dto.response.GymAdminResponse> getGymAdmins(Long gymId);
    PaginatedResponse<az.fitnest.catalog.dto.response.ReservationAdminResponse> getGymReservationsAdmin(Long gymId, az.fitnest.catalog.model.enums.ReservationStatus status, int page, int pageSize);

    az.fitnest.catalog.dto.response.ReservationDetailAdminResponse getReservationDetailAdmin(Long reservationId);

    az.fitnest.catalog.dto.response.ReservationStatsResponse getGymReservationStats(Long gymId);
    PaginatedResponse<az.fitnest.catalog.dto.response.LessonHourResponse> getGymLessonHoursAdmin(Long gymId, java.time.LocalDate startDate, java.time.LocalDate endDate, int page, int pageSize);
    az.fitnest.catalog.dto.response.LessonHourReservationCountsResponse getLessonHourReservationCounts(Long lessonHourId);
    az.fitnest.catalog.dto.response.GymWorkHoursAdminResponse getGymWorkHours(Long gymId);

    PaginatedResponse<az.fitnest.catalog.dto.response.ReservationAdminResponse> getAllReservationsAdmin(az.fitnest.catalog.model.enums.ReservationStatus status, int page, int pageSize);

    // V2 APIs
    GymDetailResponseV2 getGymDetailV2(Long userId, Long gymId);
    GymDetailResponseV3 getGymDetailV3(Long userId, Long gymId, Double lat, Double lng);
    PaginatedResponse<GymMainPageResponseV2> getGymsV2(Long userId, String q, String type, Long categoryId, Long subscriptionId, int page, int pageSize, Double userLat, Double userLng, String sortDir);
    GymInfoAdminResponseV2 getGymDetailsAdminV2(Long gymId);
    GymSubscriptionsAdminResponseV2 getGymSubscriptionsV2(Long gymId);

    PaginatedResponse<LessonHourResponse> getGymLessonHoursArchiveAdmin(Long gymId, int page, int pageSize);
}
