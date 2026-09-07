package az.fitnest.catalog.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "Public landing-page stats sourced from live catalog and package data")
public record LandingStatsResponse(
        @Schema(description = "Number of active gyms in the network")
        long gymCount,

        @Schema(description = "Number of gyms that support the Platinum package")
        long platinumGymCount,

        @Schema(description = "Monthly visit limit of the entry-level (Bronze, 1-month) package")
        int monthlyVisitLimit,

        @Schema(description = "Number of active subscription packages")
        int packageCount
) {
}
