package az.fitnest.catalog.client;

import az.fitnest.user.grpc.GetUserByIdRequest;
import az.fitnest.user.grpc.GetUsersByIdsRequest;
import az.fitnest.user.grpc.UserServiceGrpc;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class UserServiceGrpcClient {
    @GrpcClient("user-backend")
    private UserServiceGrpc.UserServiceBlockingStub userServiceStub;

    @org.springframework.cache.annotation.Cacheable(value = "users", key = "#userId")
    public CachedUser getUserById(Long userId) {
        GetUserByIdRequest request = GetUserByIdRequest.newBuilder().setUserId(userId).build();
        return CachedUser.fromProto(userServiceStub.getUserById(request));
    }

    public Map<Long, CachedUser> getUsersByIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        GetUsersByIdsRequest request = GetUsersByIdsRequest.newBuilder()
                .addAllUserIds(userIds.stream().filter(Objects::nonNull).distinct().toList())
                .build();
        if (request.getUserIdsCount() == 0) {
            return Collections.emptyMap();
        }
        return userServiceStub.getUsersByIds(request).getUsersList().stream()
                .map(CachedUser::fromProto)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(CachedUser::getUserId, Function.identity(), (left, right) -> left));
    }
}
