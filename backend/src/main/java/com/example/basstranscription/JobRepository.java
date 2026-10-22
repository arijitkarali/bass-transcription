package com.example.basstranscription;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface JobRepository extends JpaRepository<Job,UUID> {
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select j from Job j where j.id = :id")
 Optional<Job> lockById(UUID id);
}
