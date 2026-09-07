package az.fitnest.catalog.service.impl;

import az.fitnest.catalog.dto.*;
import az.fitnest.catalog.dto.request.*;
import az.fitnest.catalog.dto.response.*;
import az.fitnest.catalog.exception.ResourceNotFoundException;
import az.fitnest.catalog.model.entity.*;
import az.fitnest.catalog.repository.SavedStoreRepository;
import az.fitnest.catalog.repository.StoreRepository;
import az.fitnest.catalog.repository.TranslationRepository;
import az.fitnest.catalog.service.FileStorageService;
import az.fitnest.catalog.service.ReverseGeocodingService;
import az.fitnest.catalog.service.StoreService;
import az.fitnest.catalog.service.TranslationService;
import az.fitnest.catalog.client.OrderServiceGrpcClient;
import az.fitnest.catalog.client.UserServiceGrpcClient;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class StoreServiceImpl implements StoreService {
    private final StoreRepository storeRepository;
    private final SavedStoreRepository savedStoreRepository;
    private final TranslationRepository translationRepository;
    private final ReverseGeocodingService reverseGeocodingService;
    private final FileStorageService fileStorageService;
    private final TranslationService translationService;
    private final UserServiceGrpcClient userServiceGrpcClient;
    private final OrderServiceGrpcClient orderServiceGrpcClient;
    private final java.util.Map<Long, String> packageInfoCache = new java.util.concurrent.ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private StoreService self;

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<StoreMainPageResponse> getStores(Long userId, String q, String type, Double lat, Double lng, int page, int pageSize, String sortDir) {
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page - 1, pageSize, Sort.by(direction, "createdDate"));
        Page<Store> storePage;

        if ("SAVED".equalsIgnoreCase(type)) {
            if (userId == null) return emptyResponse(page, pageSize);
            List<SavedStore> saved = savedStoreRepository.findByUserId(userId);
            List<Store> candidates = saved.stream().map(SavedStore::getStore).toList();
            return manualPaginate(candidates, userId, lat, lng, page, pageSize, q);
        }

        if (lat != null && lng != null && "CLOSEST".equalsIgnoreCase(type)) {
            double[] bbox = boundingBox(lat, lng, 50.0);
            storePage = storeRepository.findByAddressLatitudeBetweenAndAddressLongitudeBetween(bbox[0], bbox[1], bbox[2], bbox[3], pageable);
            if (q != null && !q.isBlank()) {
                return manualPaginate(storePage.getContent(), userId, lat, lng, page, pageSize, q);
            }
        } else if ("DISCOUNTED".equalsIgnoreCase(type)) {
            storePage = (q != null && !q.isBlank()) ? storeRepository.findDiscountedStoresByQuery("%" + q.toLowerCase() + "%", pageable) : storeRepository.findDiscountedStores(pageable);
        } else if ("NEW".equalsIgnoreCase(type)) {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
            storePage = (q != null && !q.isBlank()) ? storeRepository.findNewStoresByQuery(cutoff, "%" + q.toLowerCase() + "%", pageable) : storeRepository.findNewStores(cutoff, pageable);
        } else {
            if (q != null && !q.isBlank()) {
                String pattern = "%" + q.toLowerCase() + "%";
                storePage = storeRepository.findAll((Specification<Store> & Serializable) (root, query, cb) ->
                        cb.or(cb.like(cb.lower(root.get("name")), pattern), cb.like(cb.lower(root.get("address").get("addressText")), pattern)), pageable);
            } else {
                storePage = storeRepository.findAllWithAssociations(pageable);
            }
        }

        String userLanguage = getUserLanguage(userId);
        Set<Long> savedIdsSet = Collections.emptySet();
        if (userId != null && !storePage.getContent().isEmpty()) {
            List<Long> ids = storePage.getContent().stream().map(Store::getId).toList();
            savedIdsSet = new HashSet<>(savedStoreRepository.findStoreIdsByUserIdAndStoreIdIn(userId, ids));
        }
        Map<String, String> translationMap = Collections.emptyMap();
        if (!"AZ".equalsIgnoreCase(userLanguage) && !storePage.getContent().isEmpty()) {
            List<String> idStrings = storePage.getContent().stream().map(s -> s.getId().toString()).toList();
            List<Translation> translations = translationRepository.findByEntityTypeAndEntityIdInAndLanguageCode("STORE", idStrings, userLanguage);
            translationMap = new HashMap<>();
            for (Translation t : translations) {
                translationMap.put(t.getEntityId() + "_" + t.getFieldName().toLowerCase(), t.getFieldValue());
            }
        }

        final Set<Long> finalSavedIdsSet = savedIdsSet;
        final Map<String, String> finalTranslationMap = translationMap;
        List<StoreMainPageResponse> items = storePage.getContent().stream()
                .map(s -> mapToSummary(s, finalSavedIdsSet.contains(s.getId()), userLanguage, finalTranslationMap, lat, lng))
                .collect(Collectors.toList());

        return PaginatedResponse.<StoreMainPageResponse>builder()
                .items(items)
                .total(storePage.getTotalElements())
                .page(page)
                .pageSize(pageSize)
                .build();
    }

    private PaginatedResponse<StoreMainPageResponse> manualPaginate(List<Store> candidates, Long userId, Double lat, Double lng, int page, int pageSize, String q) {
        Stream<Store> stream = candidates.stream();
        if (q != null && !q.isBlank()) {
            String lowerQ = q.toLowerCase();
            stream = stream.filter(s -> (s.getName() != null && s.getName().toLowerCase().contains(lowerQ)) ||
                    (s.getAddress() != null && s.getAddress().getAddressText() != null && s.getAddress().getAddressText().toLowerCase().contains(lowerQ)));
        }

        List<Store> filtered = stream.collect(Collectors.toList());
        if (lat != null && lng != null) {
            filtered.sort((s1, s2) -> {
                Double d1 = (s1.getAddress() != null && s1.getAddress().getLatitude() != null && s1.getAddress().getLongitude() != null)
                        ? calculateDistance(lat, lng, s1.getAddress().getLatitude(), s1.getAddress().getLongitude()) : Double.MAX_VALUE;
                Double d2 = (s2.getAddress() != null && s2.getAddress().getLatitude() != null && s2.getAddress().getLongitude() != null)
                        ? calculateDistance(lat, lng, s2.getAddress().getLatitude(), s2.getAddress().getLongitude()) : Double.MAX_VALUE;
                return d1.compareTo(d2);
            });
        }

        int from = Math.max(0, (page - 1) * pageSize);
        int to = Math.min(filtered.size(), from + pageSize);
        List<Store> pageEntities = from >= filtered.size() ? new ArrayList<>() : new ArrayList<>(filtered.subList(from, to));

        String userLanguage = getUserLanguage(userId);
        Set<Long> savedIdsSet = Collections.emptySet();
        if (userId != null && !pageEntities.isEmpty()) {
            List<Long> ids = pageEntities.stream().map(Store::getId).toList();
            savedIdsSet = new HashSet<>(savedStoreRepository.findStoreIdsByUserIdAndStoreIdIn(userId, ids));
        }
        Map<String, String> translationMap = Collections.emptyMap();
        if (!"AZ".equalsIgnoreCase(userLanguage) && !pageEntities.isEmpty()) {
            List<String> idStrings = pageEntities.stream().map(s -> s.getId().toString()).toList();
            List<Translation> translations = translationRepository.findByEntityTypeAndEntityIdInAndLanguageCode("STORE", idStrings, userLanguage);
            translationMap = new HashMap<>();
            for (Translation t : translations) {
                translationMap.put(t.getEntityId() + "_" + t.getFieldName().toLowerCase(), t.getFieldValue());
            }
        }

        final Set<Long> finalSavedIdsSet = savedIdsSet;
        final Map<String, String> finalTranslationMap = translationMap;
        List<StoreMainPageResponse> pageItems = pageEntities.stream()
                .map(s -> mapToSummary(s, finalSavedIdsSet.contains(s.getId()), userLanguage, finalTranslationMap, lat, lng))
                .collect(Collectors.toList());

        return PaginatedResponse.<StoreMainPageResponse>builder()
                .items(pageItems)
                .total(filtered.size())
                .page(page)
                .pageSize(pageSize)
                .build();
    }

    private PaginatedResponse<StoreMainPageResponse> emptyResponse(int page, int pageSize) {
        return PaginatedResponse.<StoreMainPageResponse>builder().items(Collections.emptyList()).total(0).page(page).pageSize(pageSize).build();
    }

    private String getUserLanguage(Long userId) {
        return az.fitnest.catalog.util.UserContext.getUserLanguage();
    }

    private StoreMainPageResponse mapToSummary(Store store, boolean isSaved, String userLanguage, Map<String, String> translationMap, Double lat, Double lng) {
        Double distance = null;
        if (lat != null && lng != null && store.getAddress() != null && store.getAddress().getLatitude() != null && store.getAddress().getLongitude() != null) {
            distance = calculateDistance(lat, lng, store.getAddress().getLatitude(), store.getAddress().getLongitude());
        }

        String localizedName = null;
        if (!"AZ".equalsIgnoreCase(userLanguage)) {
            localizedName = translationMap.get(store.getId() + "_name");
        }
        if (localizedName == null) {
            localizedName = translationService.getTranslatedValue("STORE", store.getId().toString(), "name", userLanguage);
        }
        if (localizedName == null || localizedName.isEmpty()) localizedName = store.getName();

        String localizedCity = null;
        if (store.getAddress() != null) {
            if (!"AZ".equalsIgnoreCase(userLanguage)) {
                localizedCity = translationMap.get(store.getId() + "_city");
            }
            if (localizedCity == null) {
                localizedCity = getLocalizedAddressField(store.getId(), "STORE", store.getAddress(), "city", userLanguage);
            }
        }

        String localizedAddressText = null;
        if (store.getAddress() != null) {
            if (!"AZ".equalsIgnoreCase(userLanguage)) {
                localizedAddressText = translationMap.get(store.getId() + "_addresstext");
            }
            if (localizedAddressText == null) {
                localizedAddressText = getLocalizedAddressField(store.getId(), "STORE", store.getAddress(), "addressText", userLanguage);
            }
        }

        return StoreMainPageResponse.builder()
                .storeId(store.getId())
                .name(localizedName)
                .coverImageUrl(store.getCoverImageUrl())
                .city(localizedCity)
                .addressText(localizedAddressText)
                .discountAppliesTo(store.getDiscounts() != null ? store.getDiscounts().stream().map(d -> StoreMainPageResponse.DiscountDto.builder().packageName(resolvePackageName(d.getPackageId(), d.getAppliesTo())).discountPercent(d.getPercent()).build()).toList() : Collections.emptyList())
                .social(store.getSocialLink() != null ? store.getSocialLink().getUrl() : null)
                .isSaved(isSaved)
                .isNew(isNew(store))
                .build();
    }

    private String resolvePackageName(Long packageId, String defaultAppliesTo) {
        if (defaultAppliesTo != null && !defaultAppliesTo.isBlank()) {
            return defaultAppliesTo;
        }
        if (packageId == null) return "Paket";
        
        if (packageInfoCache.size() >= 10000) {
            packageInfoCache.clear();
        }
        
        return packageInfoCache.computeIfAbsent(packageId, id -> {
            try {
                java.util.List<az.fitnest.order.grpc.PackageNameInfo> infos = orderServiceGrpcClient.getPackageNamesByIds(java.util.List.of(id));
                if (infos != null && !infos.isEmpty()) {
                    return infos.get(0).getName();
                }
            } catch (Exception ignored) {}

            switch (id.intValue()) {
                case 1: return "Bronze";
                case 2: return "Silver";
                case 3: return "Gold";
                case 4: return "Platinum";
                default: return "Paket " + id;
            }
        });
    }

    private boolean isNew(Store store) {
        return store != null && store.getCreatedDate() != null && store.getCreatedDate().isAfter(LocalDateTime.now().minusDays(30));
    }

    @Override
    @org.springframework.cache.annotation.Cacheable(value = "stores-details-base", key = "#storeId + '_' + #userLanguage")
    @Transactional(readOnly = true)
    public StoreDetailResponse getStoreDetailBase(Long storeId, String userLanguage) {
        Store store = storeRepository.findByIdWithAssociations(storeId).orElseThrow(() -> new ResourceNotFoundException("STORE_NOT_FOUND", "error.store_not_found"));
        String localizedName = translationService.getTranslatedValue("STORE", store.getId().toString(), "name", userLanguage);
        if (localizedName == null || localizedName.isEmpty()) localizedName = store.getName();
        return StoreDetailResponse.builder()
                .storeId(store.getId())
                .name(localizedName)
                .address(store.getAddress() != null ? AddressResponse.builder()
                        .addressText(getLocalizedAddressField(store.getId(), "STORE", store.getAddress(), "addressText", userLanguage))
                        .city(getLocalizedAddressField(store.getId(), "STORE", store.getAddress(), "city", userLanguage))
                        .latitude(store.getAddress().getLatitude())
                        .longitude(store.getAddress().getLongitude())
                        .build() : null)
                .phone(store.getPhone())
                .category(store.getCategory())
                .status(translationService.getTranslatedValue("STORE_STATUS", store.getStatus(), "name", userLanguage) != null ? translationService.getTranslatedValue("STORE_STATUS", store.getStatus(), "name", userLanguage) : store.getStatus())
                .discounts(store.getDiscounts() != null ? store.getDiscounts().stream().map(d -> StoreDiscountResponse.builder().percent(d.getPercent()).appliesTo(d.getAppliesTo()).build()).toList() : Collections.emptyList())
                .social(store.getSocialLink() != null ? StoreSocialDto.builder().name(store.getSocialLink().getName()).url(store.getSocialLink().getUrl()).build() : null)
                .images(store.getImages() != null ? store.getImages().stream().map(StoreImage::getUrl).toList() : Collections.emptyList())
                .isSaved(false)
                .isNew(isNew(store))
                .build();
      }

      @Override
      @Transactional(readOnly = true)
      public StoreDetailResponse getStoreDetail(Long userId, Long storeId) {
          String userLanguage = getUserLanguage(userId);
          StoreDetailResponse base = self.getStoreDetailBase(storeId, userLanguage);
          boolean isSaved = false;
          if (userId != null) {
              isSaved = !savedStoreRepository.findStoreIdsByUserIdAndStoreIdIn(userId, List.of(storeId)).isEmpty();
          }
          return StoreDetailResponse.builder()
                  .storeId(base.storeId())
                  .name(base.name())
                  .address(base.address())
                  .phone(base.phone())
                  .category(base.category())
                  .status(base.status())
                  .discounts(base.discounts())
                  .social(base.social())
                  .images(base.images())
                  .isSaved(isSaved)
                  .isNew(base.isNew())
                  .build();
      }

    @Override
    @Transactional
    public boolean toggleSave(Long userId, Long storeId) {
        Optional<SavedStore> existing = savedStoreRepository.findByUserIdAndStoreId(userId, storeId);
        if (existing.isPresent()) {
            savedStoreRepository.delete(existing.get());
            return false;
        } else {
            Store store = storeRepository.findById(storeId).orElseThrow(() -> new ResourceNotFoundException("STORE_NOT_FOUND", "error.store_not_found"));
            SavedStore saved = new SavedStore();
            saved.setUserId(userId);
            saved.setStore(store);
            savedStoreRepository.save(saved);
            return true;
        }
    }

    @Override
    public FilterResponse getFilters() {
        return FilterResponse.builder()
                .tabs(List.of(new FilterResponse.TabDto("all", "All"), new FilterResponse.TabDto("popular", "Popular"),
                        new FilterResponse.TabDto("nearby", "Near you"), new FilterResponse.TabDto("new", "New")))
                .sortOptions(List.of("popular", "newest", "distance", "discount_desc", "name_asc"))
                .defaultRadiusKm(20)
                .build();
    }

    @Override
    @Transactional
    @CacheEvict(value = {"admin-stores", "stores-details-base", "stores-locations", "landing-stores", "landing-home-stores", "landing-store-detail", "landing-media-public"}, allEntries = true)
    public StoreDetailResponse createStore(StoreRequest request) {
        Store store = new Store();
        updateStoreFromRequest(store, request);
        Store saved = storeRepository.save(store);
        
        translationService.autoTranslateAndSave("STORE", saved.getId().toString(), "name", request.name());
        if (saved.getAddress() != null) {
            translationService.autoTranslateAndSave("STORE", saved.getId().toString(), "addressText", saved.getAddress().getAddressText());
            translationService.autoTranslateAndSave("STORE", saved.getId().toString(), "city", saved.getAddress().getCity());
        }
        
        return getStoreDetail(null, saved.getId());
    }

    @Override
    @Transactional
    @CacheEvict(value = {"admin-stores", "stores-details-base", "stores-locations", "landing-stores", "landing-home-stores", "landing-store-detail", "landing-media-public"}, allEntries = true)
    public StoreDetailResponse updateStore(Long storeId, StoreRequest request) {
        Store store = storeRepository.findById(storeId).orElseThrow(() -> new ResourceNotFoundException("STORE_NOT_FOUND", "error.store_not_found"));
        updateStoreFromRequest(store, request);
        Store saved = storeRepository.save(store);
        
        translationService.autoTranslateAndSave("STORE", saved.getId().toString(), "name", request.name());
        if (saved.getAddress() != null) {
            translationService.autoTranslateAndSave("STORE", saved.getId().toString(), "addressText", saved.getAddress().getAddressText());
            translationService.autoTranslateAndSave("STORE", saved.getId().toString(), "city", saved.getAddress().getCity());
        }
        
        return getStoreDetail(null, saved.getId());
    }

    @Override
    @Transactional
    @CacheEvict(value = {"admin-stores", "stores-details-base", "stores-locations", "landing-stores", "landing-home-stores", "landing-store-detail", "landing-media-public"}, allEntries = true)
    public void deleteStore(Long storeId) {

        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> {
                    return new ResourceNotFoundException("STORE_NOT_FOUND", "error.store_not_found");
                });

        savedStoreRepository.deleteByStoreId(storeId);

        try {
            translationRepository.deleteByEntityTypeAndEntityId("STORE", storeId.toString());
        } catch (Exception ignored) {}

        List<String> filesToDelete = new ArrayList<>();
        if (store.getCoverImageUrl() != null) {
            filesToDelete.add(store.getCoverImageUrl());
        }

        if (store.getImages() != null && !store.getImages().isEmpty()) {
            store.getImages().stream()
                    .map(StoreImage::getUrl)
                    .filter(url -> url != null && !url.isBlank())
                    .forEach(filesToDelete::add);
        }
        fileStorageService.deleteFilesAfterCommit(filesToDelete);

        try {
            storeRepository.deleteStoreSocialLinksByStoreId(storeId);
        } catch (Exception e) {
        }

        storeRepository.delete(store);
    }

    private void updateStoreFromRequest(Store store, StoreRequest request) {
        store.setName(request.name());
        Double reqLat = request.address() != null ? request.address().latitude() : null;
        Double reqLng = request.address() != null ? request.address().longitude() : null;

        boolean coordsChanged = store.getAddress() == null || !Objects.equals(store.getAddress().getLatitude(), reqLat) || !Objects.equals(store.getAddress().getLongitude(), reqLng);

        GeocodingResponse geocoding = (store.getAddress() != null && !coordsChanged)
                ? GeocodingResponse.builder().addressText(store.getAddress().getAddressText()).city(store.getAddress().getCity()).build()
                : resolveGeocoding(reqLat, reqLng);

        store.setAddress(request.address() != null ? new Address(geocoding.addressText(), geocoding.city(), reqLat, reqLng) : null);

        store.setPhone(request.phone());
        store.setCategory(request.category());
        store.setStatus(request.status());

        if (request.social() != null) {
            store.setSocialLink(new StoreSocialLink(request.social().name(), request.social().url()));
        } else {
            store.setSocialLink(null);
        }
    }

    private GeocodingResponse resolveGeocoding(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) return GeocodingResponse.builder().build();
        return reverseGeocodingService.reverseGeocode(latitude, longitude);
    }

    private Double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (double) Math.round(R * c * 10.0) / 10.0;
    }

    private double[] boundingBox(double lat, double lng, double radiusKm) {
        double latRadians = Math.toRadians(lat);
        double radiusRatio = radiusKm / 6371.0;
        double minLat = lat - Math.toDegrees(radiusRatio);
        double maxLat = lat + Math.toDegrees(radiusRatio);
        double deltaLng = Math.toDegrees(Math.asin(Math.sin(radiusRatio) / Math.cos(latRadians)));
        return new double[]{minLat, maxLat, lng - deltaLng, lng + deltaLng};
    }

    @Override
    @Transactional
    @CacheEvict(value = "stores-details-base", allEntries = true)
    public String uploadStoreImage(Long storeId, MultipartFile file) {
        Store store = storeRepository.findById(storeId).orElseThrow(() -> new ResourceNotFoundException("STORE_NOT_FOUND", "error.store_not_found"));
        String fullUrl = fileStorageService.saveFile(file, "/stores/" + storeId);
        StoreImage image = new StoreImage();
        image.setUrl(fullUrl);
        image.setType("gallery");
        if (store.getImages() == null) store.setImages(new LinkedHashSet<>());
        store.getImages().add(image);
        storeRepository.save(store);
        return fullUrl;
    }

    @Override
    @Transactional(readOnly = true)
    @org.springframework.cache.annotation.Cacheable(value = "stores-locations", key = "#storeId")
    public LocationResponse getStoreLocation(Long storeId) {
        Store store = getStoreEntityById(storeId);
        Address addr = store.getAddress();
        if (addr == null) return LocationResponse.builder().build();
        return LocationResponse.builder().addressText(addr.getAddressText()).latitude(addr.getLatitude()).longitude(addr.getLongitude()).build();
    }

    @Override
    @Transactional(readOnly = true)
    public Store getStoreEntityById(Long storeId) {
        return storeRepository.findById(storeId).orElseThrow(() -> new ResourceNotFoundException("STORE_NOT_FOUND", "error.store_not_found"));
    }

    @Override
    public void deleteFileSafely(String url) {
        try {
            fileStorageService.deleteFile(url);
        } catch (Exception ignored) {
        }
    }

    @Override
    public String uploadFileDirectly(Long storeId, MultipartFile file) {
        return fileStorageService.saveFile(file, "/stores/" + storeId);
    }

    @Override
    @Transactional
    @CacheEvict(value = {"admin-stores", "stores-details-base", "landing-stores", "landing-home-stores", "landing-store-detail", "landing-media-public"}, allEntries = true)
    public void updateStoreCoverImageUrl(Long storeId, String coverImageUrl) {
        Store store = getStoreEntityById(storeId);
        store.setCoverImageUrl(coverImageUrl);
        storeRepository.save(store);
    }

    @Override
    @Transactional
    @CacheEvict(value = {"admin-stores", "stores-details-base", "stores-locations", "landing-stores", "landing-home-stores", "landing-store-detail", "landing-media-public"}, allEntries = true)
    public void deleteAllStores() {
        List<Store> stores = storeRepository.findAll();
        for (Store store : stores) {
            deleteStore(store.getId());
        }
    }

    @Override
    @Transactional
    @CacheEvict(value = {"admin-stores", "stores-details-base", "landing-stores", "landing-home-stores", "landing-store-detail", "landing-media-public"}, allEntries = true)
    public void addDiscount(Long storeId, AddDiscountRequest request) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("STORE_NOT_FOUND", "error.store_not_found"));

        boolean exists = orderServiceGrpcClient.checkPackageExists(request.packageId());
        if (!exists) {
            throw new ResourceNotFoundException("PACKAGE_NOT_FOUND", "error.package_not_found");
        }

        StoreDiscount discount = new StoreDiscount(request.packageId(), request.percent());
        store.getDiscounts().add(discount);
        storeRepository.save(store);
    }

    private String getLocalizedAddressField(Long entityId, String entityType, az.fitnest.catalog.model.entity.Address address, String fieldName, String userLanguage) {
        if (address == null) return null;
        String localized = translationService.getTranslatedValue(entityType, entityId.toString(), fieldName, userLanguage);
        if (localized == null || localized.isEmpty()) {
            try {
                java.lang.reflect.Field f = address.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object v = f.get(address);
                if (v != null) return v.toString();
            } catch (Exception ignored) {
            }
            return null;
        }
        return localized;
    }
}
