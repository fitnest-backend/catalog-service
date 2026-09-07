package az.fitnest.catalog.service.impl;

import az.fitnest.catalog.client.UserServiceGrpcClient;
import az.fitnest.catalog.dto.*;
import az.fitnest.catalog.dto.request.*;
import az.fitnest.catalog.dto.response.*;
import az.fitnest.catalog.dto.response.GymReviewAuthorResponse;
import az.fitnest.catalog.dto.response.GymReviewResponse;
import az.fitnest.catalog.dto.PaginatedResponse;
import az.fitnest.catalog.dto.request.ReviewRequest;
import az.fitnest.catalog.exception.ResourceNotFoundException;
import az.fitnest.catalog.mapper.GymMapper;
import az.fitnest.catalog.model.entity.Gym;
import az.fitnest.catalog.model.entity.Review;
import az.fitnest.catalog.repository.GymRepository;
import az.fitnest.catalog.repository.ReviewRepository;
import az.fitnest.catalog.client.CachedUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GymReviewServiceImpl implements az.fitnest.catalog.service.GymReviewService {
    private final GymRepository gymRepository;
    private final ReviewRepository reviewRepository;
    private final UserServiceGrpcClient userServiceGrpcClient;
    private final az.fitnest.catalog.service.TranslationService translationService;
    private final az.fitnest.catalog.client.NotificationsServiceGrpcClient notificationsServiceClient;

    @Transactional(readOnly = true)
    public PaginatedResponse<GymReviewResponse> getReviews(Long gymId, int page, int pageSize, String sort) {
        if (!gymRepository.existsById(gymId)) {
            throw new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found");
        }
        Page<Review> reviewPage = reviewRepository.findByGymId(gymId, pageable(page, pageSize, sortForReviews(sort)));
        List<GymReviewResponse> items = reviewPage.getContent().stream()
                .map(this::mapReviewToDto)
                .collect(Collectors.toList());
        return PaginatedResponse.<GymReviewResponse>builder()
                .items(items)
                .total(reviewPage.getTotalElements())
                .page(page)
                .pageSize(pageSize)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<GymReviewResponse> getGymReviewsAdmin(Long gymId, az.fitnest.catalog.model.enums.ReviewStatus status, String search, int page, int pageSize, String sort) {
        if (!gymRepository.existsById(gymId)) {
            throw new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found");
        }

        Pageable pageable = pageable(page, pageSize, sortForReviews(sort));
        Page<Review> reviewPage = reviewRepository.findByGymIdAndStatusAndSearch(gymId, status, search, pageable);

        List<GymReviewResponse> items = reviewPage.getContent().stream()
                .map(this::mapReviewToDto)
                .collect(Collectors.toList());

        return PaginatedResponse.<GymReviewResponse>builder()
                .items(items)
                .total(reviewPage.getTotalElements())
                .page(page)
                .pageSize(pageSize)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public GymReviewResponse getReviewDetail(Long reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("REVIEW_NOT_FOUND", "error.review_not_found"));
        return mapReviewToDto(review);
    }

    @Transactional
    @CacheEvict(cacheNames = "gym-detail", allEntries = true)
    public void addReview(Long userId, Long gymId, ReviewRequest request) {
        if (!gymRepository.existsById(gymId)) {
            throw new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found");
        }
        Gym gym = gymRepository.findById(gymId).orElseThrow(() -> new ResourceNotFoundException("GYM_NOT_FOUND", "error.gym_not_found"));
        Review review = new Review();
        review.setUserId(userId);
        review.setGymId(gymId);
        review.setGym(gym);
        review.setRating(request.rating());
        review.setComment(request.comment());
        review.setStatus(az.fitnest.catalog.model.enums.ReviewStatus.PENDING);
        reviewRepository.save(review);
    }

    @Transactional
    @CacheEvict(cacheNames = "gym-detail", allEntries = true)
    public Long approveReview(Long reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("REVIEW_NOT_FOUND", "error.review_not_found"));

        if (review.getStatus() != az.fitnest.catalog.model.enums.ReviewStatus.ACCEPTED) {
            reviewRepository.updateStatus(reviewId, az.fitnest.catalog.model.enums.ReviewStatus.ACCEPTED);
            reviewRepository.flush();
            recalculateGymRating(review.getGymId());

            sendReviewStatusNotification(review, true);
        }
        return review.getGymId();
    }

    @Transactional
    @CacheEvict(cacheNames = "gym-detail", allEntries = true)
    public void rejectReview(Long reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("REVIEW_NOT_FOUND", "error.review_not_found"));
        if (review.getStatus() != az.fitnest.catalog.model.enums.ReviewStatus.REJECTED) {
            reviewRepository.updateStatus(reviewId, az.fitnest.catalog.model.enums.ReviewStatus.REJECTED);
            reviewRepository.flush();
            recalculateGymRating(review.getGymId());

            sendReviewStatusNotification(review, false);
        }
    }

    private void recalculateGymRating(Long gymId) {
        java.util.Map<String, Object> stats = reviewRepository.getRatingAndCountByGymId(gymId);
        Double avgRating = 0.0;
        Long totalCount = 0L;
        if (stats != null) {
            Number avg = (Number) stats.get("avgRating");
            Number cnt = (Number) stats.get("totalCount");
            if (avg != null) {
                avgRating = avg.doubleValue();
            }
            if (cnt != null) {
                totalCount = cnt.longValue();
            }
        }
        Gym gym = gymRepository.findById(gymId).orElse(null);
        if (gym != null) {
            gym.setRating(avgRating);
            gym.setReviewsCount(totalCount.intValue());
            gymRepository.save(gym);
        }
    }

    private void sendReviewStatusNotification(Review review, boolean approved) {
        if (review == null || review.getUserId() == null) {
            return;
        }

        final Long userId = review.getUserId();
        final String gymName = (review.getGym() != null) ? review.getGym().getName() : "";

        // Resolve language
        String userLang = "AZ";
        try {
            az.fitnest.catalog.client.CachedUser user = userServiceGrpcClient.getUserById(userId);
            if (user != null && user.getLanguage() != null && !user.getLanguage().isEmpty()) {
                userLang = user.getLanguage().toUpperCase();
            }
        } catch (Exception e) {
            // Ignored
        }

        // Determine title and body based on approval status and language
        final String title;
        final String body;

        if (approved) {
            switch (userLang) {
                case "EN":
                    title = "Review Approved";
                    body = String.format("Your review for %s has been approved.", gymName);
                    break;
                case "RU":
                    title = "Отзыв одобрен";
                    body = String.format("Ваш отзыв о спортзале %s был одобрен.", gymName);
                    break;
                case "AZ":
                default:
                    title = "Rəyiniz təsdiqləndi";
                    body = String.format("Sizin %s idman zalına yazdığınız rəy təsdiqləndi.", gymName);
                    break;
            }
        } else {
            switch (userLang) {
                case "EN":
                    title = "Review Rejected";
                    body = String.format("Your review for %s has been rejected.", gymName);
                    break;
                case "RU":
                    title = "Отзыв отклонен";
                    body = String.format("Ваш отзыв о спортзале %s был отклонен.", gymName);
                    break;
                case "AZ":
                default:
                    title = "Rəyiniz rədd edildi";
                    body = String.format("Sizin %s idman zalına yazdığınız rəy rədd edildi.", gymName);
                    break;
            }
        }

        // Send push notification after transaction commits
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        triggerPushNotificationAsync(userId, title, body);
                    }
                }
            );
        } else {
            triggerPushNotificationAsync(userId, title, body);
        }
    }

    private void triggerPushNotificationAsync(Long userId, String title, String body) {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                notificationsServiceClient.sendPushNotification(userId, title, body);
            } catch (Exception e) {
                // Ignored
            }
        });
    }

    @Transactional(readOnly = true)
    public PaginatedResponse<GymReviewResponse> getPendingReviews(int page, int pageSize) {
        Page<Review> reviewPage = reviewRepository.findByStatus(az.fitnest.catalog.model.enums.ReviewStatus.PENDING, pageable(page, pageSize, Sort.by(Sort.Direction.DESC, "createdDate")));
        List<GymReviewResponse> items = reviewPage.getContent().stream()
                .map(this::mapReviewToDto)
                .collect(Collectors.toList());
        return PaginatedResponse.<GymReviewResponse>builder()
                .items(items)
                .total(reviewPage.getTotalElements())
                .page(page)
                .pageSize(pageSize)
                .build();
    }

    private GymReviewResponse mapReviewToDto(Review r) {
        CachedUser user = null;
        String fullName = "";
        String avatarUrl = null;
        try {
            if (r.getUserId() != null) {
                user = userServiceGrpcClient.getUserById(r.getUserId());
                if (user != null) {
                    fullName = user.getFirstName() + " " + user.getLastName();
                    avatarUrl = user.getProfileImageUrl();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch user {} from UserService: {}", r.getUserId(), e.getMessage());
            fullName = "User " + r.getUserId();
        }
        // Admin UI keys off machine status (PENDING/ACCEPTED/REJECTED); keep enum name, not translated label.
        String originalStatus = r.getStatus() != null ? r.getStatus().name() : null;
        return GymMapper.toReviewDto(r, fullName, avatarUrl, originalStatus);
    }

    @Transactional(readOnly = true)
    public Double getGymRating(Long gymId) {
        return gymRepository.findById(gymId)
                .map(gym -> gym.getRating() != null ? gym.getRating() : 0.0)
                .orElse(0.0);
    }

    private GymReviewResponse toGymReviewDto(Review r) {
        String originalStatus = r.getStatus() != null ? r.getStatus().name() : null;
        return new GymReviewResponse(
                r.getId(),
                r.getId() != null ? r.getId().toString() : null,
                r.getRating(),
                r.getComment(),
                GymReviewAuthorResponse.builder()
                        .user_id(r.getUserId() != null ? r.getUserId().toString() : null)
                        .full_name("User " + r.getUserId())
                        .build(),
                originalStatus,
                r.getCreatedDate() != null ? r.getCreatedDate().toLocalDate() : null
        );
    }

    private String resolveUserLanguage() {
        return az.fitnest.catalog.util.UserContext.getUserLanguage();
    }

    private Pageable pageable(int page, int size, Sort sort) {
        int safePage = Math.max(page, 1) - 1;
        int safeSize = Math.max(1, Math.min(size, 100));
        return PageRequest.of(safePage, safeSize, sort);
    }

    private Sort sortForReviews(String sort) {
        if ("newest".equalsIgnoreCase(sort)) {
            return Sort.by(Sort.Direction.DESC, "createdDate");
        } else if ("oldest".equalsIgnoreCase(sort)) {
            return Sort.by(Sort.Direction.ASC, "createdDate");
        } else if ("highest".equalsIgnoreCase(sort)) {
            return Sort.by(Sort.Direction.DESC, "rating");
        } else if ("lowest".equalsIgnoreCase(sort)) {
            return Sort.by(Sort.Direction.ASC, "rating");
        } else if ("gym_asc".equalsIgnoreCase(sort)) {
            return Sort.by(Sort.Direction.ASC, "gym.name");
        } else if ("gym_desc".equalsIgnoreCase(sort)) {
            return Sort.by(Sort.Direction.DESC, "gym.name");
        }
        return Sort.by(Sort.Direction.DESC, "createdDate");
    }
}
