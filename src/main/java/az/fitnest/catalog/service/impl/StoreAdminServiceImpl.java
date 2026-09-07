package az.fitnest.catalog.service.impl;

import az.fitnest.catalog.client.StorageGrpcClient;
import az.fitnest.catalog.dto.PaginatedResponse;
import az.fitnest.catalog.dto.request.DiscountItemRequest;
import az.fitnest.catalog.dto.request.StoreStep2Request;
import az.fitnest.catalog.dto.request.StoreStep3Request;
import az.fitnest.catalog.dto.request.StoreUpdateRequest;
import az.fitnest.catalog.dto.response.AdminStoreDetailResponse;
import az.fitnest.catalog.dto.response.AdminStoreResponse;
import az.fitnest.catalog.exception.BadRequestException;
import az.fitnest.catalog.exception.ResourceNotFoundException;
import az.fitnest.catalog.model.entity.Address;
import az.fitnest.catalog.model.entity.Store;
import az.fitnest.catalog.model.entity.StoreDiscount;
import az.fitnest.catalog.model.entity.StoreImage;
import az.fitnest.catalog.model.entity.StoreSocialLink;
import az.fitnest.catalog.model.entity.StoreWorkHours;
import az.fitnest.catalog.model.enums.StoreStatus;
import az.fitnest.catalog.repository.SavedStoreRepository;
import az.fitnest.catalog.repository.StoreRepository;
import az.fitnest.catalog.repository.TranslationRepository;
import az.fitnest.catalog.service.FileStorageService;
import az.fitnest.catalog.service.StoreAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StoreAdminServiceImpl implements StoreAdminService {

    private static final String STORE_COVER_DIR = "stores/covers";
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm");

    private final StoreRepository storeRepository;
    private final StorageGrpcClient storageGrpcClient;
    private final SavedStoreRepository savedStoreRepository;
    private final TranslationRepository translationRepository;
    private final FileStorageService fileStorageService;
    private final jakarta.persistence.EntityManager entityManager;

    @Override
    @Transactional
    @CacheEvict(value = {"admin-stores", "stores-details-base", "stores-locations", "admin-store-details", "landing-stores", "landing-store-detail"}, allEntries = true)
    public Long createMarketStep1(String name, MultipartFile photo) {

        String coverUrl = uploadAndGetUrl(photo);

        Store store = Store.builder()
                .name(name)
                .status(StoreStatus.DRAFT.name())
                .coverImageUrl(coverUrl)
                .build();

        Store saved = storeRepository.saveAndFlush(store);
        return saved.getId();
    }

    @Override
    @Transactional
    @CacheEvict(value = {"admin-stores", "stores-details-base", "stores-locations", "admin-store-details", "landing-stores", "landing-store-detail"}, allEntries = true)
    public void createMarketStep2(Long id, StoreStep2Request request) {

        Store store = findById(id);

        Address address = store.getAddress() != null
                ? store.getAddress()
                : new Address();
        address.setLatitude(request.getLatitude());
        address.setLongitude(request.getLongitude());
        if (request.getAddress() != null && !request.getAddress().isBlank()) {
            address.setAddressText(request.getAddress());
        }
        if (address.getCity() == null || address.getCity().isBlank()) {
            address.setCity("Bakı");
        }
        store.setAddress(address);

        store.setPhone(request.getPhone());
        store.setEmail(request.getEmail() != null && request.getEmail().isBlank() ? null : request.getEmail());

        if (request.getSocialUrl() != null) {
            StoreSocialLink social = new StoreSocialLink();
            social.setUrl(request.getSocialUrl());
            store.setSocialLink(social);
        }

        if (request.getWorkHours() != null) {
            StoreWorkHours wh = new StoreWorkHours();
            wh.setFromTime(LocalTime.parse(request.getWorkHours().getFrom(), TIME_FMT));
            wh.setToTime(LocalTime.parse(request.getWorkHours().getTo(),   TIME_FMT));
            store.setWorkHours(wh);
        }

        storeRepository.save(store);
    }

    @Override
    @Transactional
    @CacheEvict(value = {"admin-stores", "stores-details-base", "stores-locations", "admin-store-details", "landing-stores", "landing-store-detail"}, allEntries = true)
    public void createMarketStep3(Long id, StoreStep3Request request) {

        // 1. Atomically delete all existing store discount rows via bulk native query to avoid row-by-row cascading overhead and version locking conflicts
        storeRepository.deleteStoreDiscountsByStoreId(id);

        // 2. Clear first-level persistence cache to decouple cached references and load fresh collections directly from database
        entityManager.clear();

        Store store = findById(id);

        for (DiscountItemRequest item : request.getDiscounts()) {
            StoreDiscount discount = new StoreDiscount(
                    item.getPackageId(),
                    item.getDiscountPercent()
            );
            store.getDiscounts().add(discount);
        }

        store.setStatus(StoreStatus.ACTIVE.name());

        storeRepository.save(store);
    }

    @Override
    @CacheEvict(value = {"admin-stores", "stores-details-base", "stores-locations", "admin-store-details", "landing-stores", "landing-store-detail"}, allEntries = true)
    public void updateStoreStatus(Long storeId, String status) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new RuntimeException("Store not found with ID: " + storeId));
        store.setStatus(status);
        storeRepository.save(store);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "admin-stores", key = "{#query, #sort, #page, #pageSize}")
    public PaginatedResponse<AdminStoreResponse> getAllStoresAdmin(String query, String sort, int page, int pageSize) {
        Sort springSort = Sort.unsorted();
        if (sort != null) {
            switch (sort) {
                case "name_asc":
                    springSort = Sort.by(Sort.Direction.ASC, "name");
                    break;
                case "name_desc":
                    springSort = Sort.by(Sort.Direction.DESC, "name");
                    break;
                case "address_asc":
                    springSort = Sort.by(Sort.Direction.ASC, "address.city", "address.addressText");
                    break;
                case "newest":
                    springSort = Sort.by(Sort.Direction.DESC, "createdDate");
                    break;
                default:
                    springSort = Sort.by(Sort.Direction.DESC, "createdDate");
            }
        } else {
            springSort = Sort.by(Sort.Direction.DESC, "createdDate");
        }

        Pageable pageable = PageRequest.of(Math.max(0, page - 1), pageSize, springSort);

        Specification<Store> spec = (root, criteriaQuery, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

            if (query != null && !query.isBlank()) {
                String pattern = "%" + query.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern),
                        cb.like(cb.lower(root.get("address").get("addressText")), pattern),
                        cb.like(cb.lower(root.get("address").get("city")), pattern)
                ));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        Page<Store> storePage = storeRepository.findAll(spec, pageable);

        List<AdminStoreResponse> items = storePage.getContent().stream().map(store -> {
            String fullAddress = (store.getAddress() != null)
                    ? (store.getAddress().getCity() + ", " + store.getAddress().getAddressText())
                    : "N/A";

            return AdminStoreResponse.builder()
                    .id(store.getId())
                    .name(store.getName())
                    .fullAddress(fullAddress)
                    .phone(store.getPhone())
                    .status(store.getStatus())
                    .build();
        }).collect(Collectors.toList());

        return PaginatedResponse.<AdminStoreResponse>builder()
                .items(items)
                .total(storePage.getTotalElements())
                .page(page)
                .pageSize(pageSize)
                .build();
    }

    @Override
    @Transactional
    @CacheEvict(value = {"admin-stores", "stores-details-base", "stores-locations", "admin-store-details", "landing-stores", "landing-store-detail"}, allEntries = true)
    public void updateStore(Long id, StoreUpdateRequest request, MultipartFile photo) {

        Store store = findById(id);

        request.getName()
                .filter(n -> !n.isBlank())
                .ifPresent(store::setName);

        if (photo != null && !photo.isEmpty()) {
            deleteOldCoverIfExists(store);
            store.setCoverImageUrl(uploadAndGetUrl(photo));
        }

        if (request.getLatitude().isPresent() || request.getLongitude().isPresent()) {
            Address address = store.getAddress() != null
                    ? store.getAddress()
                    : new Address();
            request.getLatitude() .ifPresent(address::setLatitude);
            request.getLongitude().ifPresent(address::setLongitude);
            store.setAddress(address);
        }

        request.getPhone().ifPresent(store::setPhone);
        request.getEmail().ifPresent(email -> store.setEmail(email.isBlank() ? null : email));

        if (request.isSocialUrlProvided()) {
            request.getSocialUrl().ifPresentOrElse(
                    url -> {
                        StoreSocialLink social = store.getSocialLink() != null
                                ? store.getSocialLink()
                                : new StoreSocialLink();
                        social.setUrl(url);
                        store.setSocialLink(social);
                    },
                    () -> store.setSocialLink(null)
            );
        }

        if (request.isWorkHoursProvided()) {
            request.getWorkHours().ifPresentOrElse(
                    wh -> {
                        StoreWorkHours hours = store.getWorkHours() != null
                                ? store.getWorkHours()
                                : new StoreWorkHours();
                        hours.setFromTime(LocalTime.parse(wh.getFrom(), TIME_FMT));
                        hours.setToTime(LocalTime.parse(wh.getTo(),     TIME_FMT));
                        store.setWorkHours(hours);
                    },
                    () -> store.setWorkHours(null)
            );
        }

        request.getDiscounts().ifPresentOrElse(discountList -> {
            // 1. Flush core store entity mutations to database first
            storeRepository.saveAndFlush(store);
            // 2. Atomically drop old discount mapping records to avoid entity version mismatches
            storeRepository.deleteStoreDiscountsByStoreId(id);
            // 3. Clear persistence session cache to uncouple stale collections
            entityManager.clear();
            // 4. Load fresh instance and attach replacement discounts
            Store reloaded = findById(id);
            discountList.forEach(item ->
                    reloaded.getDiscounts().add(
                            new StoreDiscount(item.getPackageId(), item.getDiscountPercent())
                    )
            );
            storeRepository.save(reloaded);
        }, () -> {
            storeRepository.save(store);
        });
    }

    @Override
    @Transactional
    @CacheEvict(value = {"admin-stores", "stores-details-base", "stores-locations", "admin-store-details", "landing-stores", "landing-store-detail"}, allEntries = true)
    public void deleteStore(Long id) {

        Store store = storeRepository.findByIdWithAssociations(id)
                .orElseThrow(() -> new ResourceNotFoundException("STORE_NOT_FOUND", "Mağaza tapılmadı: " + id));

        savedStoreRepository.deleteByStoreId(id);
        
        try {
            translationRepository.deleteByEntityTypeAndEntityId("STORE", id.toString());
        } catch (Exception ignored) {}

        storeRepository.deleteStoreDiscountsByStoreId(id);
        storeRepository.deleteStoreImagesByStoreId(id);

        List<String> filesToDelete = new ArrayList<>();
        if (store.getCoverImageUrl() != null) filesToDelete.add(store.getCoverImageUrl());

        if (store.getImages() != null && !store.getImages().isEmpty()) {
            store.getImages().stream()
                    .map(StoreImage::getUrl)
                    .filter(url -> url != null && !url.isBlank())
                    .forEach(filesToDelete::add);
        }

        if (store.getDiscounts() != null) store.getDiscounts().clear();
        if (store.getImages() != null) store.getImages().clear();

        storeRepository.delete(store);

        fileStorageService.deleteFilesAfterCommit(filesToDelete);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "admin-store-details", key = "#id")
    public AdminStoreDetailResponse getStoreById(Long id) {

        Store store = storeRepository.findByIdWithAssociations(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "STORE_NOT_FOUND",
                        "Mağaza tapılmadı: " + id
                ));

        return toAdminDetailResponse(store);
    }

    private AdminStoreDetailResponse toAdminDetailResponse(Store store) {

        return AdminStoreDetailResponse.builder()
                .id(store.getId())
                .name(store.getName())
                .status(store.getStatus())
                .category(store.getCategory())
                .coverImageUrl(store.getCoverImageUrl())
                .address(mapAddress(store.getAddress()))
                .phone(store.getPhone())
                .email(store.getEmail())
                .socialLink(mapSocialLink(store.getSocialLink()))
                .workHours(mapWorkHours(store.getWorkHours()))
                .discounts(mapDiscounts(store.getDiscounts()))
                .images(mapImages(store.getImages()))
                .popularScore(store.getPopularScore())
                .createdDate(store.getCreatedDate())
                .lastModifiedDate(store.getLastModifiedDate())
                .createdBy(store.getCreatedBy())
                .lastModifiedBy(store.getLastModifiedBy())
                .build();
    }

    private AdminStoreDetailResponse.AddressDto mapAddress(Address address) {
        if (address == null) return null;
        return AdminStoreDetailResponse.AddressDto.builder()
                .addressText(address.getAddressText())
                .city(address.getCity())
                .latitude(address.getLatitude())
                .longitude(address.getLongitude())
                .build();
    }

    private AdminStoreDetailResponse.SocialLinkDto mapSocialLink(StoreSocialLink social) {
        if (social == null) return null;
        return AdminStoreDetailResponse.SocialLinkDto.builder()
                .name(social.getName())
                .url(social.getUrl())
                .build();
    }

    private AdminStoreDetailResponse.WorkHoursResponseDto mapWorkHours(StoreWorkHours wh) {
        if (wh == null) return null;
        return AdminStoreDetailResponse.WorkHoursResponseDto.builder()
                .from(wh.getFromTime() != null ? wh.getFromTime().format(TIME_FMT) : null)
                .to(wh.getToTime()   != null ? wh.getToTime()  .format(TIME_FMT) : null)
                .build();
    }

    private List<AdminStoreDetailResponse.DiscountResponseDto> mapDiscounts(
            java.util.Set<StoreDiscount> discounts) {
        if (discounts == null || discounts.isEmpty()) return List.of();
        return discounts.stream()
                .map(d -> AdminStoreDetailResponse.DiscountResponseDto.builder()
                        .packageId(d.getPackageId())
                        .discountPercent(d.getPercent())
                        .build())
                .toList();
    }

    private List<AdminStoreDetailResponse.StoreImageDto> mapImages(
            java.util.Set<StoreImage> images) {
        if (images == null || images.isEmpty()) return List.of();
        return images.stream()
                .map(img -> AdminStoreDetailResponse.StoreImageDto.builder()
                        .type(img.getType())
                        .title(img.getTitle())
                        .url(img.getUrl())
                        .build())
                .toList();
    }

    private Store findById(Long id) {
        return storeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("STORE_NOT_FOUND", "error.store_not_found"));
    }

    private String uploadAndGetUrl(MultipartFile photo) {
        var fileData = storageGrpcClient.uploadFile(photo, STORE_COVER_DIR);
        return storageGrpcClient.getDownloadUrl(String.valueOf(fileData.fsId()));
    }

    private void deleteOldCoverIfExists(Store store) {
        if (store.getCoverImageUrl() != null) {
            fileStorageService.deleteFilesAfterCommit(List.of(store.getCoverImageUrl()));
        }
    }

    @Override
    public void validateStep1(String name, MultipartFile photo) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("NAME_REQUIRED", "Mağaza adı boş ola bilməz");
        }
        if (photo == null || photo.isEmpty()) {
            throw new BadRequestException("PHOTO_REQUIRED", "Mağaza şəkli tələb olunur");
        }
    }

    @Override
    public void validateStep2(StoreStep2Request request) {
        if (request == null) {
            throw new BadRequestException("INVALID_REQUEST", "Sorğu məlumatları boşdur");
        }
        if (request.getLatitude() == null || request.getLongitude() == null) {
            throw new BadRequestException("COORDINATES_REQUIRED", "Koordinatlar tələb olunur");
        }
    }

    @Override
    public void validateStep3(StoreStep3Request request) {
        if (request == null || request.getDiscounts() == null || request.getDiscounts().isEmpty()) {
            throw new BadRequestException("DISCOUNTS_REQUIRED", "Ən azı bir endirim əlavə edilməlidir");
        }
    }
}
