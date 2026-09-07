package az.fitnest.catalog.repository;

import az.fitnest.catalog.model.entity.SavedGym;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SavedGymRepository extends JpaRepository<SavedGym, Long> {
    Optional<SavedGym> findByUserIdAndGymId(Long userId, Long gymId);

    List<SavedGym> findByUserId(Long userId);

    @Query("SELECT s FROM SavedGym s JOIN FETCH s.gym WHERE s.userId = :userId")
    List<SavedGym> findByUserIdWithGym(@Param("userId") Long userId);

    void deleteByGymId(Long gymId);

    void deleteByUserId(Long userId);

    boolean existsByGymId(Long gymId);

    boolean existsByUserIdAndGymId(Long userId, Long gymId);

    @Query("SELECT s.gym.id FROM SavedGym s WHERE s.userId = :userId AND s.gym.id IN :gymIds")
    List<Long> findGymIdsByUserIdAndGymIdIn(@Param("userId") Long userId, @Param("gymIds") List<Long> gymIds);
}
