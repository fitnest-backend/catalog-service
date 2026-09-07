package az.fitnest.catalog.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

@Builder
@Schema(description = "Public store card for the landing site")
public record LandingStoreResponse(
        Long storeId,
        String name,
        String coverImageUrl,
        String city,
        String addressText,
        String category,
        String phone,
        String email,
        List<String> discounts,
        Boolean isNew
) {
}
