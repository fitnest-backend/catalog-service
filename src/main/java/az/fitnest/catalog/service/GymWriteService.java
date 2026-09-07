package az.fitnest.catalog.service;

import az.fitnest.catalog.dto.request.GymAdminCreateRequest;
import az.fitnest.catalog.dto.request.GymAdminUpdateRequest;
import az.fitnest.catalog.dto.request.GymCreateCompleteRequest;
import az.fitnest.catalog.dto.request.GymCreateStep1Request;
import az.fitnest.catalog.dto.request.GymCreateStep2Request;
import az.fitnest.catalog.dto.request.GymCreateStep3Request;
import az.fitnest.catalog.dto.request.GymCreateStep6Request;
import az.fitnest.catalog.dto.request.GymCreateStep7Request;
import az.fitnest.catalog.dto.request.GymSubscriptionBenefitsUpdateRequest;
import az.fitnest.catalog.dto.request.SupportedServiceRequest;
import az.fitnest.catalog.dto.response.CheckInResponse;
import az.fitnest.catalog.dto.response.GeocodingResponse;
import az.fitnest.catalog.dto.response.GymCreateStep1Response;
import az.fitnest.catalog.dto.response.SupportedServiceResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface GymWriteService {
    void createGym(az.fitnest.catalog.dto.request.GymRequest request);

    void updateGym(Long id, az.fitnest.catalog.dto.request.GymRequest request);

    void updateGymInfo(Long id, az.fitnest.catalog.dto.request.GymInfoUpdateRequest request);

    void enableGymSubscription(Long gymId, Long subscriptionId);

    void updateGymSubscriptionBenefits(Long gymId, Long packageId, GymSubscriptionBenefitsUpdateRequest request);

    void deleteGym(Long gymId);

    boolean toggleSave(Object principal, Long gymId);

    CheckInResponse checkIn(Object principal, Long gymId);

    void addRoomImages(Long gymId, List<String> roomNames, List<MultipartFile> files);

    void deleteAllGymRooms(Long gymId);

    void deleteGymRoomById(Long gymId, Long roomId);

    void deleteRoomImageById(Long gymId, Long imageId);

    void updateRoomName(Long gymId, Long roomId, String name);

    void updateCoverImage(Long gymId, MultipartFile coverPhoto);

    void deleteAllGyms();

    void deleteGymEntranceHistory();

    void deleteGymEntranceHistory(Long gymId, Long packageId);

    void deleteGymEntranceHistoryByIds(Long gymId, java.util.List<Long> ids);

    void deleteAllGymSubscriptions(Long gymId);

    void deleteGymSubscriptionById(Long gymId, Long subscriptionId);

    void toggleGymReservation(Long gymId, boolean enabled);

    SupportedServiceResponse createSupportedService(SupportedServiceRequest request, MultipartFile icon);

    SupportedServiceResponse updateSupportedService(Long id, SupportedServiceRequest request, MultipartFile icon);

    void deleteSupportedService(Long id);

    GymCreateStep1Response createGymStep1(GymCreateStep1Request request);

    void createGymStep2(Long id, List<String> names, List<String> surnames, List<Long> professionIds, List<String> emails, List<String> phones, List<MultipartFile> photos, List<String> lessonTypesPerTrainer);

    void createGymStep3(Long id, GymCreateStep2Request request);

    void updateGymWorkHours(Long id, GymCreateStep2Request request);

    void createGymStep4(Long id, GymCreateStep3Request request);

    void createGymStep5(Long gymId, MultipartFile coverPhoto, List<String> roomNames, List<MultipartFile> roomPhotos);

    void createGymStep6(Long gymId, GymCreateStep6Request request, List<MultipartFile> serviceIcons);

    void updateGymSubscriptions(Long gymId, GymCreateStep6Request request);

    void createGymStep7(Long gymId, GymCreateStep7Request request);

    Long createGymComplete(GymCreateCompleteRequest request, MultipartFile coverPhoto,
                           List<MultipartFile> trainerPhotos, List<MultipartFile> roomPhotos,
                           List<MultipartFile> serviceIcons);

    GeocodingResponse reverseGeocode(Double lat, Double lng);

    java.util.List<GeocodingResponse> forwardGeocode(String query);

    void toggleGymStatus(Long gymId, boolean enabled);

    void addGymAdmin(Long gymId, GymAdminCreateRequest request);

    void updateGymAdmin(Long gymId, Long adminId, GymAdminUpdateRequest request);

    void deleteGymAdmin(Long gymId, Long adminId);

    void updateReservationStatusAdmin(Long reservationId, az.fitnest.catalog.model.enums.ReservationStatus status, String reason);

    void addLessonHourAdmin(Long gymId, az.fitnest.catalog.dto.request.LessonHourRequest request);

    void deleteLessonHourAdmin(Long lessonHourId);

    void validateStep1(az.fitnest.catalog.dto.request.GymCreateStep1Request request);

    void validateStep2(List<String> emails, List<String> phones);

    void validateStep3(az.fitnest.catalog.dto.request.GymCreateStep2Request request);

    void validateStep4(az.fitnest.catalog.dto.request.GymCreateStep3Request request);

    void validateStep5(MultipartFile coverPhoto, List<String> roomNames, List<MultipartFile> roomPhotos);

    void validateStep6(GymCreateStep6Request request, List<MultipartFile> serviceIcons);

    void validateStep7(az.fitnest.catalog.dto.request.GymCreateStep7Request request);

    // V2 APIs
    GymCreateStep1Response createGymStep1V2(az.fitnest.catalog.dto.request.GymCreateStep1RequestV2 request);
    void validateStep1V2(az.fitnest.catalog.dto.request.GymCreateStep1RequestV2 request);
    void createGymStep5V2(Long gymId, MultipartFile coverPhoto, List<MultipartFile> categoryCovers, List<Long> categoryCoverCategoryIds, List<String> roomNames, List<Long> roomCategoryIds, List<MultipartFile> roomPhotos);
    void validateStep5V2(MultipartFile coverPhoto, List<MultipartFile> categoryCovers, List<Long> categoryCoverCategoryIds, List<String> roomNames, List<Long> roomCategoryIds, List<MultipartFile> roomPhotos);
    void createGymStep6V2(Long gymId, az.fitnest.catalog.dto.request.GymCreateStep6RequestV2 request, List<MultipartFile> serviceIcons);
    void validateStep6V2(az.fitnest.catalog.dto.request.GymCreateStep6RequestV2 request, List<MultipartFile> serviceIcons);
    void updateGymSubscriptionsV2(Long gymId, az.fitnest.catalog.dto.request.GymCreateStep6RequestV2 request);
    void updateGymInfoV2(Long id, az.fitnest.catalog.dto.request.GymInfoUpdateRequestV2 request);
    Long createGymCompleteV2(az.fitnest.catalog.dto.request.GymCreateCompleteRequestV2 request, MultipartFile coverPhoto,
                             List<MultipartFile> categoryCovers, List<Long> categoryCoverCategoryIds,
                             List<MultipartFile> trainerPhotos, List<MultipartFile> roomPhotos,
                             List<MultipartFile> serviceIcons);
}
