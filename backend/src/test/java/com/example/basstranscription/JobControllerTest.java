package com.example.basstranscription;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayInputStream;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JobControllerTest {
 @TempDir Path storage;

 @Test void archiveContainsNumberedMidiAndWavAndCleansTemporaryFile() throws Exception {
  java.util.Map<UUID,Job> records=new java.util.HashMap<>();
  for(String name:java.util.List.of("Song","Song (2)")) {
   UUID id=UUID.randomUUID(); Job job=new Job(id,"inputs/clip.wav","Song");
   job.downloadName=name; job.apply(Job.Status.COMPLETED,4L,JobService.expectedResultPath(id),null);
   Path result=storage.resolve(job.resultPath); Files.createDirectories(result.getParent());
   Files.write(result,new byte[]{'M','T','h','d'}); Files.write(result.getParent().resolve("bass.wav"),new byte[]{'R','I','F','F'});
   records.put(id,job);
  }
  JobRepository repository=(JobRepository)Proxy.newProxyInstance(JobRepository.class.getClassLoader(),new Class[]{JobRepository.class},
   (proxy,method,args)->java.util.Optional.ofNullable(records.get(args[0])));
  JobController controller=new JobController(null,repository,storage.toString());
  var selection=new JobController.Selection(new java.util.ArrayList<>(records.keySet()));
  var response=controller.downloadSelected(selection,"both");
  java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
  response.getBody().writeTo(output);
  java.util.Set<String> names=new java.util.HashSet<>();
  try(var zip=new java.util.zip.ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
   for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) {
    names.add(entry.getName()); assertEquals(4,zip.readAllBytes().length);
   }
  }
  assertEquals(java.util.Set.of("Song.mid","Song.wav","Song (2).mid","Song (2).wav"),names);
  try(var files=Files.list(storage.resolve("exports"))) { assertEquals(0,files.count()); }
  records.values().iterator().next().status=Job.Status.PROCESSING;
  assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,()->controller.downloadSelected(selection,"both")).getStatusCode());
 }

 @Test void uploadAcceptsBelowTwentyMbAndRejectsAtBoundary() throws Exception {
  JobService service=new JobService(null,null) {
   @Override public Job create(UUID id,String path,String filename) {
    return new Job(id,path,filename);
   }
  };
  JobController controller=new JobController(service,null,storage.toString());
  for(long size:new long[]{19_999_999L,20_000_000L,20_000_001L}) {
   MultipartFile file=(MultipartFile)Proxy.newProxyInstance(
    MultipartFile.class.getClassLoader(),new Class[]{MultipartFile.class},
    (proxy,method,args)->switch(method.getName()) {
     case "isEmpty" -> false;
     case "getSize" -> size;
     case "getOriginalFilename" -> "minute.wav";
     case "getInputStream" -> new ByteArrayInputStream(new byte[]{1});
     default -> throw new UnsupportedOperationException(method.getName());
    });
   if(size<20_000_000L) {
    assertEquals(HttpStatus.ACCEPTED,controller.upload(file).getStatusCode());
   } else {
    ResponseStatusException error=assertThrows(ResponseStatusException.class,()->controller.upload(file));
    assertEquals(HttpStatus.CONTENT_TOO_LARGE,error.getStatusCode());
    assertEquals("File must be smaller than 20 MB",error.getReason());
   }
  }
 }

 @Test void completedJobCanDownloadOnlyItsOwnMidi() throws Exception {
  UUID id=UUID.randomUUID();
  Job job=new Job(id,"inputs/clip.wav","clip");
  job.apply(Job.Status.COMPLETED,100L,JobService.expectedResultPath(id),null);
  JobRepository repository=(JobRepository)Proxy.newProxyInstance(
   JobRepository.class.getClassLoader(),new Class[]{JobRepository.class},
   (proxy,method,args)-> {
    if(method.getName().equals("findById")) return Optional.of(job);
    throw new UnsupportedOperationException(method.getName());
   });
  JobController controller=new JobController(null,repository,storage.toString());
  Path midi=storage.resolve(job.resultPath);
  Files.createDirectories(midi.getParent());
  Files.write(midi,new byte[]{'M','T','h','d'});
  assertArrayEquals(new byte[]{'M','T','h','d'},
   controller.midi(id).getBody().getInputStream().readAllBytes());
  assertEquals("audio/midi",controller.midi(id).getHeaders().getContentType().toString());
  Files.write(midi.getParent().resolve("bass.wav"),new byte[]{'R','I','F','F'});
  for(String name:new String[]{"clip","My song.take 2","Bäss \"riff\"",null,"", "C:\\music\\clip", "../clip", "clip\r\nInjected"}) {
   job.userFileName=name;
   String expected=name==null || name.isBlank() ? id.toString()
    : name.equals("C:\\music\\clip") || name.equals("../clip") ? "clip"
    : name.replace('\r','_').replace('\n','_');
   assertEquals(expected+".mid",controller.midi(id).getHeaders().getContentDisposition().getFilename());
   assertEquals(expected+".wav",controller.bass(id).getHeaders().getContentDisposition().getFilename());
  }
  job.resultPath="results/another-job/bass.mid";
  ResponseStatusException error=assertThrows(ResponseStatusException.class,()->controller.midi(id));
  assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
 }
}
