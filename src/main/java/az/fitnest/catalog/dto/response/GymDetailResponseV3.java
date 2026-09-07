package az.fitnest.catalog.dto.response;

import az.fitnest.catalog.model.enums.GymStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.List;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GymDetailResponseV3(
        String gym_id,
        String name,
        String description,
        LocationResponse address,
        Boolean isSaved,
        Boolean isOpen,
        String openUntil,
        Boolean isNew,
        Double distanceKm,
        String phone,
        String email,
        List<CategoryResponse> categories,
        String coverImageUrl,
        String logoUrl,
        Double rating,
        Integer reviewsCount,
        String qr_code_url,
        GymStatus status,
        GymStartingPriceResponse startingFrom,
        List<GymPlanBenefitResponse> amenities
) {
}
