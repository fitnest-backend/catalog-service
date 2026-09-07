package az.fitnest.catalog.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "Public gym card for the landing site")
public record LandingGymResponse(
        String gymId,
        String name,
        String coverImageUrl,
        String location,
        String city,
        String phone,
        String email,
        String workHoursText,
        String category,
        String membership,
        String description
) {
}
