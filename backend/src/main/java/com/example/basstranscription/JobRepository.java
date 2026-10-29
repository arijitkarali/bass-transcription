package com.example.basstranscription;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
public interface JobRepository extends JpaRepository<Job,UUID> {
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select j from Job j where j.id = :id")
 Optional<Job> lockById(UUID id);
 @Query("select j from Job j where (:status is null or j.status = :status) and (" +
  "lower(coalesce(j.userFileName, '')) like lower(concat('%', :search, '%')) or " +
  "lower(j.downloadName) like lower(concat('%', :search, '%')) or " +
  "cast(j.id as string) like concat('%', :search, '%'))")
 Page<Job> search(Job.Status status,String search,Pageable pageable);
 long countByStatus(Job.Status status);
 List<Job> findTop30ByStatusInOrderByUpdatedAtDescIdDesc(Collection<Job.Status> statuses);
}
