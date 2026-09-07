package az.fitnest.catalog.dto.response;

import lombok.Builder;

@Builder
public record GymStartingPriceResponse(
        String planId,
        String packageName,
        Double dailyPrice
) {
}
