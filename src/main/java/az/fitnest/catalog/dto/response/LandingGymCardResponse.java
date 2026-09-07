package az.fitnest.catalog.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "Public gym card for homepage and gym listing. No gallery, coordinates, or contact extras.")
public record LandingGymCardResponse(
        String gymId,
        String name,
        String coverImageUrl,
        String location,
        String city,
        String phone,
        String category,
        String membership
) {
}
