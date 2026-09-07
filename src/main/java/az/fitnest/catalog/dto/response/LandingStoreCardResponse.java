package az.fitnest.catalog.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

@Builder
@Schema(description = "Public FitStore card for homepage and store listing. No email.")
public record LandingStoreCardResponse(
        Long storeId,
        String name,
        String coverImageUrl,
        String city,
        String addressText,
        String category,
        String phone,
        String workHoursText,
        List<String> discounts,
        Boolean isNew
) {
}
