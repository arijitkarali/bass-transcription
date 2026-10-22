package com.example.basstranscription;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
@RestController @RequestMapping("/api/jobs")
class JobController {
 private static final long MAX_UPLOAD_BYTES=6_000_000L;
 private static final Set<String> ACCEPTED_EXTENSIONS=Set.of(".wav", ".aif", ".mp3");
 private final JobService service; private final JobRepository jobs; private final Path root;
 JobController(JobService service,JobRepository jobs,@Value("${app.storage-root}") String root) throws IOException {
  this.service=service; this.jobs=jobs; this.root=Path.of(root).toAbsolutePath().normalize();
  Files.createDirectories(this.root.resolve("inputs"));
 }
 @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
 ResponseEntity<Job> upload(@RequestParam("file") MultipartFile file) throws IOException {
  if(file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"File is empty");
  if(file.getSize()>=MAX_UPLOAD_BYTES)
   throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,"File must be smaller than 6 MB");
  String originalFileName = file.getOriginalFilename();
  String extension = extensionOf(originalFileName);
  
  if(!ACCEPTED_EXTENSIONS.contains(extension))
   throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"Only WAV, AIF, and MP3 files are accepted");

  String originalFileNameWithoutExt = getOriginalFileNameWithoutExt(originalFileName);
  
  var id=UUID.randomUUID(); String relative="inputs/"+id+extension; Path target=root.resolve(relative);
  try(var input=file.getInputStream()) { Files.copy(input,target); }
  // A DB failure can leave an orphan file, but never a committed job pointing to a deleted upload.
  Job job=service.create(id,relative,originalFileNameWithoutExt);
  return ResponseEntity.accepted().location(URI.create("/api/jobs/"+id)).body(job);
 }

 private static String getOriginalFileNameWithoutExt(String fileName)
 {
  int nameLength = fileName.lastIndexOf('.');
  if(nameLength >0) return fileName.substring(0,nameLength);
  else return "";
 }

 private static String extensionOf(String filename) {
  if(filename==null) return "";
  int dot=filename.lastIndexOf('.');
  return dot<0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
 }
 @GetMapping("/{id}") Job get(@PathVariable UUID id) {
  return jobs.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Job not found"));
 }
 @GetMapping("/{id}/midi") ResponseEntity<Resource> midi(@PathVariable UUID id) throws IOException {
  Job job=get(id);
  if(job.status!=Job.Status.COMPLETED || !JobService.expectedResultPath(id).equals(job.resultPath))
   throw new ResponseStatusException(HttpStatus.CONFLICT,"MIDI is not ready");
  Path file=root.resolve(job.resultPath);
  if(!Files.isRegularFile(file) || !file.toRealPath().startsWith(root.toRealPath()))
   throw new ResponseStatusException(HttpStatus.NOT_FOUND,"MIDI file is missing");
  return ResponseEntity.ok()
   .contentType(MediaType.parseMediaType("audio/midi"))
   .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"bass-"+id+".mid\"")
   .body(new FileSystemResource(file));
 }
 @GetMapping("/{id}/bass") ResponseEntity<Resource> bass(@PathVariable UUID id) throws IOException {
  Job job=get(id);
  if(job.status!=Job.Status.COMPLETED || !JobService.expectedResultPath(id).equals(job.resultPath))
   throw new ResponseStatusException(HttpStatus.CONFLICT,"Bass stem is not ready");
  Path file=root.resolve("results").resolve(id.toString()).resolve("bass.wav");
  if(!Files.isRegularFile(file) || !file.toRealPath().startsWith(root.toRealPath()))
   throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Bass stem is missing");
  return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav"))
   .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"bass-"+id+".wav\"")
   .body(new FileSystemResource(file));
 }
}
