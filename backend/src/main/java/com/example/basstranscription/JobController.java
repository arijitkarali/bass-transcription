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
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
@RestController @RequestMapping("/api/jobs")
class JobController {
 private static final long MAX_UPLOAD_BYTES=20_000_000L;
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
   throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,"File must be smaller than 20 MB");
  String originalFileName = file.getOriginalFilename();
  String extension = extensionOf(originalFileName);
  
  if(!ACCEPTED_EXTENSIONS.contains(extension))
   throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"Only WAV, AIF, and MP3 files are accepted");

  String originalFileNameWithoutExt = getOriginalFileNameWithoutExt(originalFileName);
  if(originalFileNameWithoutExt.length()>255)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Filename without its extension must be at most 255 characters");
  
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
 private static String downloadDisposition(Job job,String extension) {
  String name=job.downloadName!=null ? job.downloadName : JobService.safeDownloadBase(job.userFileName,job.id);
  return ContentDisposition.attachment().filename(name+extension,StandardCharsets.UTF_8).build().toString();
 }
 record Dashboard(List<Job> items,long total,int page,int size,Map<String,Long> stats,List<Job> activity) {}
 @GetMapping Dashboard list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size,
   @RequestParam(required=false) Job.Status status,@RequestParam(defaultValue="") String search) {
  if(page<0 || size<1 || size>50 || search.length()>255)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page, size or search");
  var result=jobs.search(status,search,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"createdAt","id")));
  Map<String,Long> stats=new LinkedHashMap<>();
  for(Job.Status state:Job.Status.values()) stats.put(state.name(),jobs.countByStatus(state));
  return new Dashboard(result.getContent(),result.getTotalElements(),page,size,stats,
   jobs.findTop30ByStatusInOrderByUpdatedAtDescIdDesc(List.of(Job.Status.COMPLETED,Job.Status.FAILED)));
 }
 record Selection(List<UUID> ids) {}
 record Deleted(List<UUID> ids,List<String> cleanupWarnings) {}
 @PostMapping("/delete") Deleted delete(@RequestBody Selection selection) {
  List<Job> removed=service.delete(selection.ids());
  List<String> warnings=new ArrayList<>();
  for(Job job:removed) {
   try { removeFiles(job); }
   catch(IOException error) { warnings.add("Submission "+job.id+" was deleted, but some files could not be removed"); }
  }
  return new Deleted(removed.stream().map(job->job.id).toList(),warnings);
 }
 private void removeFiles(Job job) throws IOException {
  Path input=root.resolve(job.inputPath).normalize();
  if(!input.startsWith(root.resolve("inputs"))) throw new IOException("Unsafe input path");
  deleteStoredFile(input);
  Path result=root.resolve("results").resolve(job.id.toString());
  for(String filename:List.of("bass.wav","bass.mid","bass.wav.tmp","bass.mid.tmp")) deleteStoredFile(result.resolve(filename));
  if(Files.exists(result)) Files.delete(result);
 }
 private void deleteStoredFile(Path file) throws IOException {
  if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {
   if(!file.toRealPath().startsWith(root.toRealPath())) throw new IOException("Unsafe storage path");
   Files.delete(file);
  }
 }
 record ArchiveFile(Path path,String filename) {}
 @PostMapping("/downloads") ResponseEntity<StreamingResponseBody> downloadSelected(
   @RequestBody Selection selection,@RequestParam(defaultValue="both") String format) throws IOException {
  if(!Set.of("both","midi","bass").contains(format))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose MIDI, WAV or both");
  List<ArchiveFile> files=new ArrayList<>();
  for(UUID id:JobService.selection(selection.ids())) {
   Job job=get(id);
   if(job.status!=Job.Status.COMPLETED)
    throw new ResponseStatusException(HttpStatus.CONFLICT,"Only completed submissions can be downloaded");
   if(!format.equals("bass")) {
    var response=midi(id);
    files.add(new ArchiveFile(response.getBody().getFile().toPath(),response.getHeaders().getContentDisposition().getFilename()));
   }
   if(!format.equals("midi")) {
    var response=bass(id);
    files.add(new ArchiveFile(response.getBody().getFile().toPath(),response.getHeaders().getContentDisposition().getFilename()));
   }
  }
  Path exports=root.resolve("exports");
  Files.createDirectories(exports);
  Path archive=Files.createTempFile(exports,"download-",".zip");
  try {
   try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(archive),StandardCharsets.UTF_8)) {
    Set<String> names=new HashSet<>();
    for(ArchiveFile file:files) {
     String name=file.filename();
     // Also protect ZIP entries for legacy rows that do not yet have a reserved name.
     for(int suffix=2;!names.add(name);suffix++) {
      int dot=file.filename().lastIndexOf('.');
      name=file.filename().substring(0,dot)+" ("+suffix+")"+file.filename().substring(dot);
     }
     zip.putNextEntry(new ZipEntry(name));
     Files.copy(file.path(),zip);
     zip.closeEntry();
    }
   }
  } catch(IOException | RuntimeException error) { Files.deleteIfExists(archive); throw error; }
  StreamingResponseBody body=output->{
   try { Files.copy(archive,output); }
   finally { Files.deleteIfExists(archive); }
  };
  return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
   .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"bass-results.zip\"")
   .header(HttpHeaders.CACHE_CONTROL,"no-store").body(body);
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
   .header(HttpHeaders.CONTENT_DISPOSITION,downloadDisposition(job,".mid"))
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
   .header(HttpHeaders.CONTENT_DISPOSITION,downloadDisposition(job,".wav"))
   .body(new FileSystemResource(file));
 }
}
