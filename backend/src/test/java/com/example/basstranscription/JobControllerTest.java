package com.example.basstranscription;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JobControllerTest {
 @TempDir Path storage;

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
  job.resultPath="results/another-job/bass.mid";
  ResponseStatusException error=assertThrows(ResponseStatusException.class,()->controller.midi(id));
  assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
 }
}
