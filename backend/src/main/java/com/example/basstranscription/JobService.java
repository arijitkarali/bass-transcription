package com.example.basstranscription;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
@Service
class JobService {
 private final JobRepository jobs; private final JdbcTemplate sql;
 JobService(JobRepository jobs, JdbcTemplate sql) { this.jobs=jobs; this.sql=sql; }
 @Transactional public Job create(UUID id,String path, String fileName) {
  Job job=jobs.saveAndFlush(new Job(id,path,fileName));
  sql.update("insert into outbox(id,input_path) values (?,?)",id,path); return job;
 }
 record Event(int version, UUID jobId, Job.Status status, Long sizeBytes, String resultPath, String errorMessage) {}
 @Transactional public void apply(Event event) {
  if(event.version()!=1 || event.jobId()==null || event.status()==null || event.status()==Job.Status.QUEUED)
   throw new IllegalArgumentException("Invalid event");
  if(event.status()==Job.Status.COMPLETED && (event.sizeBytes()==null || event.sizeBytes()<0 ||
      !expectedResultPath(event.jobId()).equals(event.resultPath())))
   throw new IllegalArgumentException("Invalid result");
  if(event.status()==Job.Status.FAILED && (event.errorMessage()==null || event.errorMessage().length()>2000))
   throw new IllegalArgumentException("Invalid error");
  jobs.lockById(event.jobId()).orElseThrow().apply(event.status(),event.sizeBytes(),event.resultPath(),event.errorMessage());
 }
 static String expectedResultPath(UUID id) { return "results/"+id+"/bass.mid"; }
}
