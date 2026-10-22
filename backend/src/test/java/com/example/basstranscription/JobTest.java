package com.example.basstranscription;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
class JobTest {
 @Test void terminalResultsSurviveDuplicateAndLateEvents() {
  Job job=new Job(UUID.randomUUID(),"inputs/test","test");
  job.apply(Job.Status.COMPLETED,12L,"results/test/bass.mid",null);
  job.apply(Job.Status.PROCESSING,null,null,null);
  job.apply(Job.Status.FAILED,null,null,"late failure");
  assertEquals(Job.Status.COMPLETED,job.status); assertEquals(12L,job.sizeBytes);
  assertNull(job.errorMessage);
  assertEquals("results/test/bass.mid",job.resultPath);
 }
 @Test void failureRemainsTerminal() {
  Job job=new Job(UUID.randomUUID(),"inputs/test","test");
  job.apply(Job.Status.PROCESSING,null,null,null);
  job.apply(Job.Status.FAILED,null,null,"missing");
  job.apply(Job.Status.COMPLETED,4L,"results/test/bass.mid",null);
  assertEquals(Job.Status.FAILED,job.status); assertEquals("missing",job.errorMessage);
 }
}
