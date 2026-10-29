package com.example.basstranscription;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import java.util.List;
import java.util.Collection;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
@Service
class JobService {
 private final JobRepository jobs; private final JdbcTemplate sql;
 JobService(JobRepository jobs, JdbcTemplate sql) { this.jobs=jobs; this.sql=sql; }
 @Transactional public Job create(UUID id,String path, String fileName) {
  Job job=new Job(id,path,fileName);
  job.downloadName=reserveDownloadName(fileName,id);
  jobs.saveAndFlush(job);
  sql.update("insert into outbox(id,input_path) values (?,?)",id,path); return job;
 }
 static String safeDownloadBase(String name,UUID id) {
  if(name!=null) {
   name=name.replace('\\','/');
   name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","_").strip();
  }
  if(name==null || name.isBlank() || name.equals(".") || name.equals("..")) return id.toString();
  while(name.getBytes(StandardCharsets.UTF_8).length>180)
   name=name.substring(0,name.offsetByCodePoints(name.length(),-1));
  return name;
 }
 private String reserveDownloadName(String name,UUID id) {
  String base=safeDownloadBase(name,id);
  for(int number=1;;number++) {
   String candidate=base+(number==1 ? "" : " ("+number+")");
   if(sql.update("insert into download_names(name) values (?) on conflict do nothing",candidate)==1) return candidate;
  }
 }
 static List<UUID> selection(Collection<UUID> ids) {
  if(ids==null || ids.isEmpty() || ids.size()>20 || ids.stream().anyMatch(java.util.Objects::isNull))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Select between 1 and 20 submissions");
  return ids.stream().distinct().sorted().toList();
 }
 @Transactional public List<Job> delete(Collection<UUID> ids) {
  List<Job> selected=selection(ids).stream().map(id->jobs.lockById(id)
   .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"A selected submission no longer exists"))).toList();
  if(selected.stream().anyMatch(job->job.status!=Job.Status.COMPLETED && job.status!=Job.Status.FAILED))
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Queued and processing submissions cannot be deleted");
  for(Job job:selected) {
   sql.update("delete from outbox where id=?",job.id);
   jobs.delete(job);
  }
  return selected;
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
