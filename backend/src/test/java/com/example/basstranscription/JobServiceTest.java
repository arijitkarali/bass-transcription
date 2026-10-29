package com.example.basstranscription;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JobServiceTest {
 private static JobRepository repository(Map<UUID,Job> records) {
  return (JobRepository)Proxy.newProxyInstance(JobRepository.class.getClassLoader(),new Class[]{JobRepository.class},
   (proxy,method,args)->switch(method.getName()) {
    case "saveAndFlush" -> { Job job=(Job)args[0]; records.put(job.id,job); yield job; }
    case "lockById" -> Optional.ofNullable(records.get(args[0]));
    case "delete" -> records.remove(((Job)args[0]).id);
    default -> throw new UnsupportedOperationException(method.getName());
   });
 }
 private static JdbcTemplate sql(Set<String> names) {
  return new JdbcTemplate() {
   @Override public int update(String query,Object... args) {
    if(query.startsWith("insert into download_names")) return names.add(((String)args[0]).toLowerCase(Locale.ROOT)) ? 1 : 0;
    return 1;
   }
  };
 }
 @Test void repeatedNamesRemainUniqueAfterDeletionAndExplicitSuffixes() {
  Map<UUID,Job> records=new HashMap<>(); Set<String> names=new HashSet<>();
  JobService service=new JobService(repository(records),sql(names));
  Job first=service.create(UUID.randomUUID(),"inputs/one.wav","Song");
  Job second=service.create(UUID.randomUUID(),"inputs/two.wav","Song");
  Job explicit=service.create(UUID.randomUUID(),"inputs/three.wav","Song (3)");
  first.status=Job.Status.COMPLETED; service.delete(List.of(first.id));
  Job later=service.create(UUID.randomUUID(),"inputs/four.wav","song");
  assertEquals("Song",first.downloadName); assertEquals("Song (2)",second.downloadName);
  assertEquals("Song (3)",explicit.downloadName); assertEquals("song (4)",later.downloadName);
 }
 @Test void mixedActiveSelectionDeletesNothing() {
  Map<UUID,Job> records=new HashMap<>();
  JobService service=new JobService(repository(records),sql(new HashSet<>()));
  Job completed=service.create(UUID.randomUUID(),"inputs/one.wav","one"); completed.status=Job.Status.COMPLETED;
  Job active=service.create(UUID.randomUUID(),"inputs/two.wav","two");
  ResponseStatusException error=assertThrows(ResponseStatusException.class,()->service.delete(List.of(completed.id,active.id)));
  assertEquals(HttpStatus.CONFLICT,error.getStatusCode()); assertEquals(2,records.size());
 }
 @Test void selectionIsBoundedAndDeduplicated() {
  assertThrows(ResponseStatusException.class,()->JobService.selection(null));
  assertThrows(ResponseStatusException.class,()->JobService.selection(List.of()));
  assertThrows(ResponseStatusException.class,()->JobService.selection(Collections.nCopies(21,UUID.randomUUID())));
  assertThrows(ResponseStatusException.class,()->JobService.selection(Arrays.asList(UUID.randomUUID(),null)));
  UUID id=UUID.randomUUID(); assertEquals(List.of(id),JobService.selection(List.of(id,id)));
 }
 @Test void namesAreSafeAndFitFilesystemByteLimits() {
  UUID id=UUID.randomUUID();
  assertEquals("clip",JobService.safeDownloadBase("C:\\music\\clip",id));
  assertEquals("clip",JobService.safeDownloadBase("../clip",id));
  assertEquals("clip__bad",JobService.safeDownloadBase("clip\r\nbad",id));
  assertEquals(id.toString(),JobService.safeDownloadBase(null,id));
  assertEquals(id.toString(),JobService.safeDownloadBase("..",id));
  String name=JobService.safeDownloadBase("🎸".repeat(100),id);
  assertTrue(name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=180);
  assertFalse(Character.isHighSurrogate(name.charAt(name.length()-1)));
 }
}
