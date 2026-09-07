package az.fitnest.catalog.repository;

import az.fitnest.catalog.model.entity.Store;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StoreRepository
        extends JpaRepository<Store, Long>,
        JpaSpecificationExecutor<Store> {
    @Query(value = "SELECT s.*,\n(6371 * acos(cos(radians(:lat)) * cos(radians(s.address_lat)) * cos(radians(s.address_lng) - radians(:lng)) + sin(radians(:lat)) * sin(radians(s.address_lat)))) AS distance\nFROM stores s\nHAVING distance < :radius\nORDER BY distance ASC\n", nativeQuery = true)
    public Page<Store> findNearby(@Param(value = "lat") Double var1, @Param(value = "lng") Double var2, @Param(value = "radius") Double var3, Pageable var4);

    @Query(value = "SELECT s.*,\n(6371 * acos(cos(radians(:lat)) * cos(radians(s.address_lat)) * cos(radians(s.address_lng) - radians(:lng)) + sin(radians(:lat)) * sin(radians(s.address_lat)))) AS distance\nFROM stores s\n", nativeQuery = true)
    public Page<Store> findAllWithDistance(@Param(value = "lat") Double var1, @Param(value = "lng") Double var2, Pageable var3);

    public List<Store> findByAddressLatitudeBetweenAndAddressLongitudeBetween(Double var1, Double var2, Double var3, Double var4);

    public Page<Store> findByAddressLatitudeBetweenAndAddressLongitudeBetween(Double var1, Double var2, Double var3, Double var4, Pageable var5);

    @Query(value = "SELECT s FROM Store s")
    public Page<Store> findAllWithAssociations(Pageable var1);

    @EntityGraph(attributePaths = {"discounts", "images"})
    Page<Store> findByStatusIgnoreCase(String status, Pageable pageable);

    @Query(value = "SELECT s FROM Store s WHERE EXISTS (SELECT 1 FROM s.discounts d)")
    public Page<Store> findDiscountedStores(Pageable var1);

    @Query(value = "SELECT s FROM Store s WHERE (LOWER(s.name) LIKE :pattern OR LOWER(s.address.addressText) LIKE :pattern) AND EXISTS (SELECT 1 FROM s.discounts d)")
    public Page<Store> findDiscountedStoresByQuery(@Param(value = "pattern") String var1, Pageable var2);

    @EntityGraph(attributePaths = {"discounts", "images"})

    @Query(value = "SELECT s FROM Store s WHERE s.id = :id")
    public Optional<Store> findByIdWithAssociations(@Param(value = "id") Long var1);

    @Query(value = "SELECT s FROM Store s WHERE s.createdDate >= :cutoff")
    public Page<Store> findNewStores(@Param(value = "cutoff") LocalDateTime var1, Pageable var2);

    @Query(value = "SELECT s FROM Store s WHERE s.createdDate >= :cutoff AND (LOWER(s.name) LIKE :pattern OR LOWER(s.address.addressText) LIKE :pattern)")
    public Page<Store> findNewStoresByQuery(@Param(value = "cutoff") LocalDateTime var1, @Param(value = "pattern") String var2, Pageable var3);

    @Modifying
    @Query(value = "DELETE FROM store_social_links WHERE store_id = :storeId", nativeQuery = true)
    void deleteStoreSocialLinksByStoreId(@Param("storeId") Long storeId);

    @Modifying
    @Query(value = "DELETE FROM store_discounts WHERE store_id = :storeId", nativeQuery = true)
    void deleteStoreDiscountsByStoreId(@Param("storeId") Long storeId);

    @Modifying
    @Query(value = "DELETE FROM store_images WHERE store_id = :storeId", nativeQuery = true)
    void deleteStoreImagesByStoreId(@Param("storeId") Long storeId);
}
