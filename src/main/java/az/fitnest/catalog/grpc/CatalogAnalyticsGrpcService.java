package az.fitnest.catalog.grpc;

import az.fitnest.catalog.model.entity.GymAdmin;
import az.fitnest.catalog.repository.GymAdminRepository;
import az.fitnest.catalog.repository.GymAnalyticsRepository;
import az.fitnest.catalog.repository.GymAnalyticsRepository.PartnersKpiProjection;
import az.fitnest.catalog.repository.GymRepository;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * @author: nijataghayev
 */

@GrpcService
@RequiredArgsConstructor
public class CatalogAnalyticsGrpcService extends GymServiceGrpc.GymServiceImplBase {

    private final GymAnalyticsRepository gymAnalyticsRepository;
    private final GymAdminRepository gymAdminRepository;
    private final GymRepository gymRepository;

    @Override
    public void getActivePartnersKpi(
            GetActivePartnersKpiRequest request,
            StreamObserver<ActivePartnersKpiResponse> responseObserver
    ) {
        PartnersKpiProjection kpi = gymAnalyticsRepository.getActivePartnersKpi();

        responseObserver.onNext(
                ActivePartnersKpiResponse.newBuilder()
                        .setTotalActivePartners(kpi.getTotalActivePartners())
                        .setPercentageChange(kpi.getPercentageChange())
                        .build()
        );
        responseObserver.onCompleted();
    }

    @Override
    public void getGymAdminsByUsers(
            GetGymAdminsByUsersRequest request,
            StreamObserver<GetGymAdminsByUsersResponse> responseObserver
    ) {
        List<Long> userIds = request.getUserIdsList();
        List<String> phoneNumbers = request.getPhoneNumbersList();
        List<String> emails = request.getEmailsList();

        List<Long> queryUserIds = userIds.isEmpty() ? List.of(-1L) : userIds;
        List<String> queryPhones = phoneNumbers.isEmpty() ? List.of("") : phoneNumbers;
        List<String> queryEmails = emails.isEmpty() ? List.of("") : emails;

        List<GymAdmin> admins = gymAdminRepository.findAllByUserIdInOrPhoneNumberInOrEmailIn(queryUserIds, queryPhones, queryEmails);

        List<GymAdminDetail> details = admins.stream()
                .map(admin -> GymAdminDetail.newBuilder()
                        .setUserId(admin.getUserId() != null ? admin.getUserId() : 0L)
                        .setGymName(admin.getGym() != null ? admin.getGym().getName() : "")
                        .setRole(admin.getRole() != null ? admin.getRole() : "")
                        .setPhoneNumber(admin.getPhoneNumber() != null ? admin.getPhoneNumber() : "")
                        .setEmail(admin.getEmail() != null ? admin.getEmail() : "")
                        .build())
                .collect(Collectors.toList());

        responseObserver.onNext(
                GetGymAdminsByUsersResponse.newBuilder()
                        .addAllAdmins(details)
                        .build()
        );
        responseObserver.onCompleted();
    }

    @Override
    public void countGymsByPackage(
            CountGymsByPackageRequest request,
            StreamObserver<CountGymsByPackageResponse> responseObserver
    ) {
        long count = request.getPackageId() == 0
                ? 0
                : gymRepository.countActiveGymsByPackageId(request.getPackageId());
        responseObserver.onNext(
                CountGymsByPackageResponse.newBuilder()
                        .setGymCount(count)
                        .build()
        );
        responseObserver.onCompleted();
    }
}
