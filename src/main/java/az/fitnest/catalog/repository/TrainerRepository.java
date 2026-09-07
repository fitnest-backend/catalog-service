package az.fitnest.catalog.repository;

import az.fitnest.catalog.model.entity.Trainer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TrainerRepository
        extends JpaRepository<Trainer, Long> {
    @Query(value = "SELECT t FROM Trainer t WHERE t.gymId = :gymId")
    public Page<Trainer> findByGymId(@Param(value = "gymId") Long gymId, Pageable pageable);

    @Query("SELECT t FROM Trainer t LEFT JOIN FETCH t.profession WHERE t.gymId = :gymId ORDER BY t.id ASC")
    java.util.List<Trainer> findPreviewByGymId(@Param("gymId") Long gymId, Pageable pageable);

    @Modifying
    @Transactional
    @Query("UPDATE Trainer t SET t.profession = NULL")
    void clearAllProfessions();

    boolean existsByEmail(String email);
    boolean existsByPhone(String phone);
}
