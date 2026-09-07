package az.fitnest.catalog.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

@Builder
@Schema(description = "Public FitStore detail. Email is included because the store page displays it.")
public record LandingStoreDetailResponse(
        Long storeId,
        String name,
        String coverImageUrl,
        String city,
        String addressText,
        String category,
        String phone,
        String email,
        String workHoursText,
        List<String> discounts,
        Boolean isNew
) {
}
