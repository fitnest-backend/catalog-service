package az.fitnest.catalog.repository;

import az.fitnest.catalog.model.entity.Gym;
import az.fitnest.catalog.model.enums.GymStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GymRepository
        extends JpaRepository<Gym, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Gym> {

    public List<Gym> findByAddressLatitudeBetweenAndAddressLongitudeBetween(Double var1, Double var2, Double var3, Double var4);

    @org.springframework.data.jpa.repository.Query(value = "SELECT * FROM gyms WHERE latitude BETWEEN :minLat AND :maxLat AND longitude BETWEEN :minLng AND :maxLng " +
            "ORDER BY (6371 * acos(least(1, cos(radians(:userLat)) * cos(radians(latitude)) * cos(radians(longitude) - radians(:userLng)) + sin(radians(:userLat)) * sin(radians(latitude))))) ASC",
            countQuery = "SELECT count(*) FROM gyms WHERE latitude BETWEEN :minLat AND :maxLat AND longitude BETWEEN :minLng AND :maxLng", nativeQuery = true)
    public org.springframework.data.domain.Page<Gym> findClosestGyms(
            @org.springframework.data.repository.query.Param("minLat") Double minLat,
            @org.springframework.data.repository.query.Param("maxLat") Double maxLat,
            @org.springframework.data.repository.query.Param("minLng") Double minLng,
            @org.springframework.data.repository.query.Param("maxLng") Double maxLng,
            @org.springframework.data.repository.query.Param("userLat") Double userLat,
            @org.springframework.data.repository.query.Param("userLng") Double userLng,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query(value = "SELECT * FROM gyms " +
            "ORDER BY (6371 * acos(least(1, cos(radians(:userLat)) * cos(radians(latitude)) * cos(radians(longitude) - radians(:userLng)) + sin(radians(:userLat)) * sin(radians(latitude))))) ASC",
            countQuery = "SELECT count(*) FROM gyms", nativeQuery = true)
    public org.springframework.data.domain.Page<Gym> findAllClosest(
            @org.springframework.data.repository.query.Param("userLat") Double userLat,
            @org.springframework.data.repository.query.Param("userLng") Double userLng,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query(value = "SELECT * FROM gyms WHERE (LOWER(name) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) OR LOWER(description) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) OR LOWER(address_text) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%'))) " +
            "ORDER BY (6371 * acos(least(1, cos(radians(:userLat)) * cos(radians(latitude)) * cos(radians(longitude) - radians(:userLng)) + sin(radians(:userLat)) * sin(radians(latitude))))) ASC",
            countQuery = "SELECT count(*) FROM gyms WHERE (LOWER(name) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) OR LOWER(description) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) OR LOWER(address_text) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')))", nativeQuery = true)
    public org.springframework.data.domain.Page<Gym> searchClosest(
            @org.springframework.data.repository.query.Param("q") String q,
            @org.springframework.data.repository.query.Param("userLat") Double userLat,
            @org.springframework.data.repository.query.Param("userLng") Double userLng,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("SELECT w FROM Gym g JOIN g.generalWorkHours w WHERE g.id = :gymId")
    public List<az.fitnest.catalog.model.entity.GymWorkHour> findGeneralWorkHoursByGymId(
            @org.springframework.data.repository.query.Param("gymId") Long gymId);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"address", "mainCategories", "subCategories", "subscriptions", "subscriptions.supportedServices", "rooms", "rooms.images", "generalWorkHours", "workHoursWoman", "workHoursMan", "restDays"})
    public Optional<Gym> findWithDetailsById(Long id);

    long countByStatus(GymStatus status);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {
            "address",
            "mainCategories",
            "subCategories",
            "subscriptions",
            "subscriptions.supportedServices",
            "generalWorkHours",
            "workHoursWoman",
            "workHoursMan",
            "restDays"
    })
    Optional<Gym> findAboutDetailById(Long id);

    @org.springframework.data.jpa.repository.Query("SELECT g FROM Gym g WHERE (LOWER(g.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR LOWER(g.description) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR LOWER(g.address.addressText) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))")
    public org.springframework.data.domain.Page<Gym> findByNameOrDescriptionContainingIgnoreCase(
            @org.springframework.data.repository.query.Param("q") String q,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("SELECT g FROM Gym g WHERE EXISTS (SELECT mc FROM g.mainCategories mc WHERE mc.id = :categoryId) OR EXISTS (SELECT sc FROM g.subCategories sc WHERE sc.id = :categoryId)")
    public org.springframework.data.domain.Page<Gym> findByCategory(
            @org.springframework.data.repository.query.Param("categoryId") Long categoryId,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(g) > 0 FROM Gym g WHERE EXISTS (SELECT mc FROM g.mainCategories mc WHERE mc.id = :categoryId) OR EXISTS (SELECT sc FROM g.subCategories sc WHERE sc.id = :categoryId)")
    public boolean existsByCategoryId(@org.springframework.data.repository.query.Param("categoryId") Long categoryId);

    @org.springframework.data.jpa.repository.Query("SELECT g FROM Gym g WHERE (LOWER(g.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR LOWER(g.description) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR LOWER(g.address.addressText) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))) AND EXISTS (SELECT mc FROM g.mainCategories mc WHERE mc.id = :categoryId)")
    public org.springframework.data.domain.Page<Gym> findByNameOrDescriptionContainingIgnoreCaseAndCategory(
            @org.springframework.data.repository.query.Param("q") String q,
            @org.springframework.data.repository.query.Param("categoryId") Long categoryId,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query(value = "SELECT g.* FROM gyms g JOIN gym_main_categories gmc ON g.id = gmc.gym_id WHERE gmc.category_id = :categoryId " +
            "ORDER BY (6371 * acos(least(1, cos(radians(:userLat)) * cos(radians(g.latitude)) * cos(radians(g.longitude) - radians(:userLng)) + sin(radians(:userLat)) * sin(radians(g.latitude))))) ASC",
            countQuery = "SELECT count(*) FROM gyms g JOIN gym_main_categories gmc ON g.id = gmc.gym_id WHERE gmc.category_id = :categoryId", nativeQuery = true)
    public org.springframework.data.domain.Page<Gym> findByCategoryClosest(
            @org.springframework.data.repository.query.Param("categoryId") Long categoryId,
            @org.springframework.data.repository.query.Param("userLat") Double userLat,
            @org.springframework.data.repository.query.Param("userLng") Double userLng,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query(value = "SELECT g.* FROM gyms g JOIN gym_main_categories gmc ON g.id = gmc.gym_id " +
            "WHERE (LOWER(g.name) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) OR LOWER(g.description) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) OR LOWER(g.address_text) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%'))) " +
            "AND gmc.category_id = :categoryId " +
            "ORDER BY (6371 * acos(least(1, cos(radians(:userLat)) * cos(radians(g.latitude)) * cos(radians(g.longitude) - radians(:userLng)) + sin(radians(:userLat)) * sin(radians(g.latitude))))) ASC",
            countQuery = "SELECT count(*) FROM gyms g JOIN gym_main_categories gmc ON g.id = gmc.gym_id WHERE (LOWER(g.name) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) OR LOWER(g.description) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) OR LOWER(g.address_text) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%'))) AND gmc.category_id = :categoryId", nativeQuery = true)
    public org.springframework.data.domain.Page<Gym> searchClosestWithCategory(
            @org.springframework.data.repository.query.Param("q") String q,
            @org.springframework.data.repository.query.Param("categoryId") Long categoryId,
            @org.springframework.data.repository.query.Param("userLat") Double userLat,
            @org.springframework.data.repository.query.Param("userLng") Double userLng,
            org.springframework.data.domain.Pageable pageable);

    @Query("SELECT g FROM Gym g WHERE " +
            "LOWER(g.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR " +
            "LOWER(g.address.addressText) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR " +
            "EXISTS (SELECT mc FROM g.mainCategories mc WHERE LOWER(mc.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))")
    org.springframework.data.domain.Page<Gym> searchByNameAddressCategory(
            @org.springframework.data.repository.query.Param("q") String q,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query(value = "SELECT cover_image_url FROM gyms WHERE cover_image_url IS NOT NULL " +
            "UNION ALL " +
            "SELECT qr_code_url FROM gyms WHERE qr_code_url IS NOT NULL " +
            "UNION ALL " +
            "SELECT profile_image_url FROM trainers WHERE profile_image_url IS NOT NULL " +
            "UNION ALL " +
            "SELECT picture_url FROM room_images WHERE picture_url IS NOT NULL " +
            "UNION ALL " +
            "SELECT url FROM gym_images WHERE url IS NOT NULL", nativeQuery = true)
    List<String> findAllGymRelatedFileUrls();

    @org.springframework.data.jpa.repository.Query("SELECT g.qrCodeUrl FROM Gym g WHERE g.id = :gymId")
    String findQrCodeUrlById(@org.springframework.data.repository.query.Param("gymId") Long gymId);

    @org.springframework.data.jpa.repository.Query("SELECT w FROM Gym g JOIN g.generalWorkHours w WHERE g.id IN :gymIds")
    public List<az.fitnest.catalog.model.entity.GymWorkHour> findAllGeneralWorkHoursByGymIds(
            @org.springframework.data.repository.query.Param("gymIds") List<Long> gymIds);

    @org.springframework.data.jpa.repository.Query(
            value = "SELECT g FROM Gym g " +
                    "WHERE (:hasSubscriptionFilter = false OR EXISTS (SELECT s FROM g.subscriptions s WHERE s.packageId IN :subscriptionIds))",
            countQuery = "SELECT count(g) FROM Gym g WHERE (:hasSubscriptionFilter = false OR EXISTS (SELECT s FROM g.subscriptions s WHERE s.packageId IN :subscriptionIds))"
    )
    org.springframework.data.domain.Page<Gym> findAllWithSpecificationFallback(
            @org.springframework.data.repository.query.Param("hasSubscriptionFilter") boolean hasSubscriptionFilter,
            @org.springframework.data.repository.query.Param("subscriptionIds") List<Long> subscriptionIds,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"mainCategories", "subscriptions", "subscriptions.supportedServices", "generalWorkHours"})
    @org.springframework.data.jpa.repository.Query(
            value = "SELECT g FROM Gym g " +
                    "WHERE (:hasSubscriptionFilter = false OR EXISTS (SELECT s FROM g.subscriptions s WHERE s.packageId IN :subscriptionIds)) " +
                    "AND (:categoryId IS NULL OR EXISTS (SELECT mc FROM g.mainCategories mc WHERE mc.id = :categoryId)) " +
                    "AND (:q IS NULL OR LOWER(g.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR LOWER(g.description) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR LOWER(g.address.addressText) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))"
    )
    org.springframework.data.domain.Page<Gym> findAllGymsWithFilters(
            @org.springframework.data.repository.query.Param("q") String q,
            @org.springframework.data.repository.query.Param("categoryId") Long categoryId,
            @org.springframework.data.repository.query.Param("hasSubscriptionFilter") boolean hasSubscriptionFilter,
            @org.springframework.data.repository.query.Param("subscriptionIds") List<Long> subscriptionIds,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query(
            value = "SELECT g.id FROM Gym g " +
                    "WHERE g.status = az.fitnest.catalog.model.enums.GymStatus.ACTIVE " +
                    "AND (:hasSubscriptionFilter = false OR EXISTS (SELECT s FROM g.subscriptions s WHERE s.packageId IN :subscriptionIds)) " +
                    "AND (:categoryId IS NULL OR EXISTS (SELECT mc FROM g.mainCategories mc WHERE mc.id = :categoryId)) " +
                    "AND (:q IS NULL OR LOWER(g.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR LOWER(g.description) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR LOWER(g.address.addressText) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))"
    )
    org.springframework.data.domain.Page<Long> findGymIdsWithFilters(
            @org.springframework.data.repository.query.Param("q") String q,
            @org.springframework.data.repository.query.Param("categoryId") Long categoryId,
            @org.springframework.data.repository.query.Param("hasSubscriptionFilter") boolean hasSubscriptionFilter,
            @org.springframework.data.repository.query.Param("subscriptionIds") List<Long> subscriptionIds,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query(
            value = "SELECT DISTINCT g.* FROM gyms g " +
                    "LEFT JOIN gym_subscriptions gs ON g.id = gs.gym_id " +
                    "LEFT JOIN gym_main_categories gmc ON g.id = gmc.gym_id " +
                    "WHERE (:hasSubscriptionFilter = false OR gs.package_id IN :subscriptionIds) " +
                    "AND (:categoryId IS NULL OR gmc.category_id = :categoryId) " +
                    "AND (:q IS NULL OR LOWER(g.name) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) OR LOWER(g.description) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) OR LOWER(g.address_text) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%'))) " +
                    "ORDER BY (6371 * acos(least(1, cos(radians(:userLat)) * cos(radians(g.latitude)) * cos(radians(g.longitude) - radians(:userLng)) + sin(radians(:userLat)) * sin(radians(g.latitude))))) ASC",
            countQuery = "SELECT count(DISTINCT g.id) FROM gyms g " +
                    "LEFT JOIN gym_subscriptions gs ON g.id = gs.gym_id " +
                    "LEFT JOIN gym_main_categories gmc ON g.id = gmc.gym_id " +
                    "WHERE (:hasSubscriptionFilter = false OR gs.package_id IN :subscriptionIds) " +
                    "AND (:categoryId IS NULL OR gmc.category_id = :categoryId) " +
                    "AND (:q IS NULL OR LOWER(g.name) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) OR LOWER(g.description) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) OR LOWER(g.address_text) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')))",
            nativeQuery = true
    )
    org.springframework.data.domain.Page<Gym> findAllClosestWithFiltersNative(
            @org.springframework.data.repository.query.Param("q") String q,
            @org.springframework.data.repository.query.Param("categoryId") Long categoryId,
            @org.springframework.data.repository.query.Param("hasSubscriptionFilter") boolean hasSubscriptionFilter,
            @org.springframework.data.repository.query.Param("subscriptionIds") List<Long> subscriptionIds,
            @org.springframework.data.repository.query.Param("userLat") Double userLat,
            @org.springframework.data.repository.query.Param("userLng") Double userLng,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query(
            value = "SELECT g.id FROM gyms g " +
                    "LEFT JOIN gym_subscriptions gs ON g.id = gs.gym_id " +
                    "LEFT JOIN gym_main_categories gmc ON g.id = gmc.gym_id " +
                    "WHERE g.status = 'ACTIVE' " +
                    "AND (:hasSubscriptionFilter = false OR gs.package_id IN :subscriptionIds) " +
                    "AND (:categoryId IS NULL OR gmc.category_id = :categoryId) " +
                    "AND (:q IS NULL OR LOWER(g.name) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) OR LOWER(g.description) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) OR LOWER(g.address_text) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%'))) " +
                    "GROUP BY g.id, g.latitude, g.longitude " +
                    "ORDER BY (6371 * acos(least(1, cos(radians(:userLat)) * cos(radians(g.latitude)) * cos(radians(g.longitude) - radians(:userLng)) + sin(radians(:userLat)) * sin(radians(g.latitude))))) ASC",
            countQuery = "SELECT count(DISTINCT g.id) FROM gyms g " +
                    "LEFT JOIN gym_subscriptions gs ON g.id = gs.gym_id " +
                    "LEFT JOIN gym_main_categories gmc ON g.id = gmc.gym_id " +
                    "WHERE g.status = 'ACTIVE' " +
                    "AND (:hasSubscriptionFilter = false OR gs.package_id IN :subscriptionIds) " +
                    "AND (:categoryId IS NULL OR gmc.category_id = :categoryId) " +
                    "AND (:q IS NULL OR LOWER(g.name) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) OR LOWER(g.description) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) OR LOWER(g.address_text) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')))",
            nativeQuery = true
    )
    org.springframework.data.domain.Page<Long> findAllClosestWithFiltersNativeIds(
            @org.springframework.data.repository.query.Param("q") String q,
            @org.springframework.data.repository.query.Param("categoryId") Long categoryId,
            @org.springframework.data.repository.query.Param("hasSubscriptionFilter") boolean hasSubscriptionFilter,
            @org.springframework.data.repository.query.Param("subscriptionIds") List<Long> subscriptionIds,
            @org.springframework.data.repository.query.Param("userLat") Double userLat,
            @org.springframework.data.repository.query.Param("userLng") Double userLng,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"mainCategories", "subCategories", "subscriptions", "subscriptions.supportedServices", "generalWorkHours"})
    public List<Gym> findWithListDetailsByIdIn(List<Long> ids);

    @org.springframework.data.jpa.repository.Query("SELECT mc, COUNT(g.id) FROM Gym g JOIN g.mainCategories mc GROUP BY mc")
    List<Object[]> countGymsByCategory();

    @org.springframework.data.jpa.repository.Query("SELECT s.packageId, COUNT(DISTINCT s.gym.id) FROM GymSubscription s WHERE s.gym.status = az.fitnest.catalog.model.enums.GymStatus.ACTIVE GROUP BY s.packageId")
    List<Object[]> countGymsBySubscriptionPackageId();

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(g) FROM Gym g WHERE " +
            "(:categoryId IS NULL OR EXISTS (SELECT mc FROM g.mainCategories mc WHERE mc.id = :categoryId) OR EXISTS (SELECT sc FROM g.subCategories sc WHERE sc.id = :categoryId)) AND " +
            "(:subscriptionId IS NULL OR EXISTS (SELECT s FROM g.subscriptions s WHERE s.packageId = :subscriptionId)) AND " +
            "(:newThreshold IS NULL OR g.createdDate > :newThreshold)")
    long countGymsWithFilters(
            @org.springframework.data.repository.query.Param("categoryId") Long categoryId,
            @org.springframework.data.repository.query.Param("subscriptionId") Long subscriptionId,
            @org.springframework.data.repository.query.Param("newThreshold") java.time.LocalDateTime newThreshold);

    long countByCreatedDateAfter(java.time.LocalDateTime date);

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(g) FROM Gym g WHERE g.workHoursWoman IS NOT EMPTY")
    long countGymsWithWorkHoursWoman();

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(g) FROM Gym g WHERE g.workHoursMan IS NOT EMPTY")
    long countGymsWithWorkHoursMan();

    @org.springframework.data.jpa.repository.Query(
            value = "SELECT g.id FROM Gym g " +
                    "WHERE g.status = az.fitnest.catalog.model.enums.GymStatus.ACTIVE " +
                    "AND (:hasSubscriptionFilter = false OR EXISTS (SELECT s FROM g.subscriptions s WHERE s.packageId IN :subscriptionIds)) " +
                    "AND (:categoryId IS NULL OR EXISTS (SELECT mc FROM g.mainCategories mc WHERE mc.id = :categoryId) OR EXISTS (SELECT sc FROM g.subCategories sc WHERE sc.id = :categoryId)) " +
                    "AND (:q IS NULL OR LOWER(g.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR LOWER(g.description) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR LOWER(g.address.addressText) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))"
    )
    org.springframework.data.domain.Page<Long> findGymIdsWithFiltersV2(
            @org.springframework.data.repository.query.Param("q") String q,
            @org.springframework.data.repository.query.Param("categoryId") Long categoryId,
            @org.springframework.data.repository.query.Param("hasSubscriptionFilter") boolean hasSubscriptionFilter,
            @org.springframework.data.repository.query.Param("subscriptionIds") List<Long> subscriptionIds,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query(
            value = "SELECT g.id FROM gyms g " +
                    "LEFT JOIN gym_subscriptions gs ON g.id = gs.gym_id " +
                    "LEFT JOIN gym_main_categories gmc ON g.id = gmc.gym_id " +
                    "LEFT JOIN gym_sub_categories gsc ON g.id = gsc.gym_id " +
                    "WHERE g.status = 'ACTIVE' " +
                    "AND (:hasSubscriptionFilter = false OR gs.package_id IN :subscriptionIds) " +
                    "AND (:categoryId IS NULL OR gmc.category_id = :categoryId OR gsc.category_id = :categoryId) " +
                    "AND (:q IS NULL OR LOWER(g.name) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) OR LOWER(g.description) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) OR LOWER(g.address_text) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%'))) " +
                    "GROUP BY g.id, g.latitude, g.longitude " +
                    "ORDER BY (6371 * acos(least(1, cos(radians(:userLat)) * cos(radians(g.latitude)) * cos(radians(g.longitude) - radians(:userLng)) + sin(radians(:userLat)) * sin(radians(g.latitude))))) ASC",
            countQuery = "SELECT count(DISTINCT g.id) FROM gyms g " +
                    "LEFT JOIN gym_subscriptions gs ON g.id = gs.gym_id " +
                    "LEFT JOIN gym_main_categories gmc ON g.id = gmc.gym_id " +
                    "LEFT JOIN gym_sub_categories gsc ON g.id = gsc.gym_id " +
                    "WHERE g.status = 'ACTIVE' " +
                    "AND (:hasSubscriptionFilter = false OR gs.package_id IN :subscriptionIds) " +
                    "AND (:categoryId IS NULL OR gmc.category_id = :categoryId OR gsc.category_id = :categoryId) " +
                    "AND (:q IS NULL OR LOWER(g.name) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) OR LOWER(g.description) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) OR LOWER(g.address_text) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')))",
            nativeQuery = true
    )
    org.springframework.data.domain.Page<Long> findAllClosestWithFiltersNativeIdsV2(
            @org.springframework.data.repository.query.Param("q") String q,
            @org.springframework.data.repository.query.Param("categoryId") Long categoryId,
            @org.springframework.data.repository.query.Param("hasSubscriptionFilter") boolean hasSubscriptionFilter,
            @org.springframework.data.repository.query.Param("subscriptionIds") List<Long> subscriptionIds,
            @org.springframework.data.repository.query.Param("userLat") Double userLat,
            @org.springframework.data.repository.query.Param("userLng") Double userLng,
            org.springframework.data.domain.Pageable pageable);

    @Query(value = """
            SELECT CASE WHEN EXISTS (
                SELECT 1 FROM gyms g
                WHERE g.status = 'ACTIVE'
                  AND g.cover_image_url IS NOT NULL
                  AND (
                      g.cover_image_url = :fileId
                      OR RIGHT(g.cover_image_url, LENGTH(:fileId) + 1) = CONCAT('/', :fileId)
                  )
            ) THEN TRUE ELSE FALSE END
            """, nativeQuery = true)
    boolean existsActivePublicCoverFile(@org.springframework.data.repository.query.Param("fileId") String fileId);
}