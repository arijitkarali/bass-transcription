package com.example.basstranscription;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="jobs")
public class Job {
 @Id public UUID id;
 @Enumerated(EnumType.STRING) @Column(nullable=false) public Status status;
 @Column(nullable=false) public String inputPath;
 @Column public String userFileName;
 @Column(nullable=false,unique=true) public String downloadName;
 public String resultPath;
 @Column(length=2000) public String errorMessage;
 public Long sizeBytes;
 @Column(nullable=false) public Instant createdAt;
 @Column(nullable=false) public Instant updatedAt;
 public enum Status { QUEUED, PROCESSING, COMPLETED, FAILED }
 protected Job() {}
 Job(UUID id, String path, String fileName) { this.id=id; inputPath=path; userFileName=fileName; status=Status.QUEUED; createdAt=updatedAt=Instant.now(); }
 void apply(Status next, Long size, String result, String error) {
  if(status==Status.COMPLETED || status==Status.FAILED || next==Status.QUEUED) return;
  status=next; sizeBytes=next==Status.COMPLETED ? size : null;
  resultPath=next==Status.COMPLETED ? result : null;
  errorMessage=next==Status.FAILED ? error : null; updatedAt=Instant.now();
 }
}
