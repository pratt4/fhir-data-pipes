/*
 * Copyright 2020-2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.fhir.analytics;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import ca.uhn.fhir.context.FhirContext;
import com.google.api.services.storage.model.Objects;
import com.google.api.services.storage.model.StorageObject;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.apache.beam.sdk.extensions.gcp.options.GcsOptions;
import org.apache.beam.sdk.extensions.gcp.util.GcsUtil;
import org.apache.beam.sdk.extensions.gcp.util.GcsUtil.CreateOptions;
import org.apache.beam.sdk.extensions.gcp.util.GcsUtil.StorageObjectOrIOException;
import org.apache.beam.sdk.extensions.gcp.util.gcsfs.GcsPath;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.util.MimeTypes;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

public class GcsDwhFilesTest {

  @Mock private GcsUtil mockGcsUtil;
  private AutoCloseable closeable;

  @BeforeEach
  public void setUp() {
    closeable = MockitoAnnotations.openMocks(this);
    GcsOptions gcsOptions = PipelineOptionsFactory.as(GcsOptions.class);
    gcsOptions.setGcsUtil(mockGcsUtil);
    FileSystems.setDefaultPipelineOptions(gcsOptions);
  }

  @AfterEach
  public void closeService() throws Exception {
    closeable.close();
  }

  @Test
  public void getResourcePathTest() {
    DwhFiles dwhFiles =
        DwhFiles.forRoot("gs://testbucket/testdirectory", FhirContext.forR4Cached());
    ResourceId resourceId = dwhFiles.getResourcePath("Patient");
    assertThat(resourceId.toString(), equalTo("gs://testbucket/testdirectory/Patient/"));
  }

  @Test
  public void newIncrementalRunPathTest() throws IOException {
    DwhFiles dwhFiles =
        DwhFiles.forRoot("gs://testbucket/testdirectory", FhirContext.forR4Cached());

    List<StorageObjectOrIOException> items = new ArrayList<>();
    items.add(StorageObjectOrIOException.create(new FileNotFoundException()));
    Mockito.when(mockGcsUtil.getObjects(Mockito.anyList())).thenReturn(items);

    ResourceId resourceId = dwhFiles.newIncrementalRunPath();
    assertThat(
        resourceId.toString(),
        startsWith("gs://testbucket/testdirectory/incremental_run" + DwhFiles.TIMESTAMP_PREFIX));
  }

  @Test
  public void findNonEmptyFhirResourceTypes_fewResourcesToCopy_Success() throws IOException {
    Objects modelObjects = new Objects();
    List<StorageObject> items = new ArrayList<>();
    // Files within the directory
    items.add(
        createStorageObject(
            "gs://testbucket/testdirectory/Patient/patient.parquet", 1L /* fileSize */));
    items.add(
        createStorageObject(
            "gs://testbucket/testdirectory/Observation/observation.parquet", 2L /* fileSize */));
    modelObjects.setItems(items);

    Mockito.when(
            mockGcsUtil.listObjects(
                Mockito.eq("testbucket"), Mockito.anyString(), Mockito.isNull(String.class)))
        .thenReturn(modelObjects);

    DwhFiles dwhFiles =
        DwhFiles.forRoot("gs://testbucket/testdirectory", FhirContext.forR4Cached());
    Set<String> resourceTypes = dwhFiles.findNonEmptyResourceDirs();
    assertThat("Could not find Patient", resourceTypes.contains("Patient"));
    assertThat("Could not find Observation", resourceTypes.contains("Observation"));
    assertThat(resourceTypes.size(), equalTo(2));
  }

  @Test
  public void findNonEmptyFhirResourceTypes_NoResourcesToCopy_Success() throws IOException {
    // Empty list
    Objects modelObjects = new Objects();
    Mockito.when(
            mockGcsUtil.listObjects(
                Mockito.eq("testbucket"), Mockito.anyString(), Mockito.isNull(String.class)))
        .thenReturn(modelObjects);

    DwhFiles dwhFiles =
        DwhFiles.forRoot("gs://testbucket/testdirectory", FhirContext.forR4Cached());
    Set<String> resourceTypes = dwhFiles.findNonEmptyResourceDirs();
    assertThat(resourceTypes.size(), equalTo(0));
  }

  @Test
  public void copyResourceTypeTest() throws IOException {

    DwhFiles sourceDwhFiles =
        DwhFiles.forRoot("gs://testbucket/source-test-directory", FhirContext.forR4Cached());
    DwhFiles destDwhFiles =
        DwhFiles.forRoot("gs://testbucket/dest-test-directory", FhirContext.forR4Cached());
    Objects modelObjects = new Objects();
    List<StorageObject> items = new ArrayList<>();
    // Files within the directory
    items.add(
        createStorageObject(
            "gs://testbucket/source-test-directory/Patient/patient.parquet", 1L /* fileSize */));
    modelObjects.setItems(items);

    Mockito.when(
            mockGcsUtil.listObjects(
                Mockito.eq("testbucket"), Mockito.anyString(), Mockito.isNull(String.class)))
        .thenReturn(modelObjects);
    DwhFiles.copyDirToDwh(sourceDwhFiles.getRoot(), "Patient", destDwhFiles.getRoot());

    Mockito.verify(mockGcsUtil, Mockito.times(1))
        .copy(
            Collections.singletonList(
                "gs://testbucket/source-test-directory/Patient/patient.parquet"),
            Collections.singletonList(
                "gs://testbucket/dest-test-directory/Patient/patient.parquet"));
  }

  @Test
  public void writeTimestampFile_FileAlreadyExists_ThrowsError() throws IOException {
    String gcsFileName = "gs://testbucket/testdirectory/timestamp_start.txt";
    List<StorageObjectOrIOException> items = new ArrayList<>();
    // Files within the directory
    items.add(
        StorageObjectOrIOException.create(createStorageObject(gcsFileName, 1L /* fileSize */)));
    Mockito.when(mockGcsUtil.getObjects(List.of(GcsPath.fromUri(gcsFileName)))).thenReturn(items);

    Assertions.assertThrows(
        FileAlreadyExistsException.class,
        () ->
            DwhFiles.writeTimestampFile(
                "gs://testbucket/testdirectory", DwhFiles.TIMESTAMP_FILE_START));
  }

  @Test
  public void writeTimestampFile_FileDoesNotExist_CreatesFile() throws IOException {
    String gcsFileName = "gs://testbucket/testdirectory/timestamp_start.txt";
    // Empty directory
    List<StorageObjectOrIOException> items = new ArrayList<>();
    items.add(StorageObjectOrIOException.create(new FileNotFoundException()));
    Mockito.when(mockGcsUtil.getObjects(List.of(GcsPath.fromUri(gcsFileName)))).thenReturn(items);

    WritableByteChannel writableByteChannel = Channels.newChannel(OutputStream.nullOutputStream());
    Mockito.when(
            mockGcsUtil.create(
                GcsPath.fromUri(gcsFileName),
                CreateOptions.builder().setContentType(MimeTypes.BINARY).build()))
        .thenReturn(writableByteChannel);

    DwhFiles.writeTimestampFile("gs://testbucket/testdirectory", DwhFiles.TIMESTAMP_FILE_START);

    Mockito.verify(mockGcsUtil, Mockito.times(1)).getObjects(List.of(GcsPath.fromUri(gcsFileName)));
    Mockito.verify(mockGcsUtil, Mockito.times(1))
        .create(
            GcsPath.fromUri(gcsFileName),
            CreateOptions.builder().setContentType(MimeTypes.BINARY).build());
  }

  @Test
  public void readTimestampFile() throws IOException {
    String gcsFileName = "gs://testbucket/testdirectory/timestamp_start.txt";
    Instant currentInstant = Instant.now();
    mockFileRead(gcsFileName, currentInstant);

    Instant actualInstant =
        DwhFiles.readTimestampFile("gs://testbucket/testdirectory", DwhFiles.TIMESTAMP_FILE_START);

    Assertions.assertEquals(currentInstant.getEpochSecond(), actualInstant.getEpochSecond());
    Mockito.verify(mockGcsUtil, Mockito.times(1)).open(GcsPath.fromUri(gcsFileName));
  }

  @Test
  public void dwhRootPrefixForGCSPath_returnsFileSeparator() {
    String fileSeparator = DwhFiles.getFileSeparatorForDwhFiles("gs://testbucket/testdirectory");
    Assertions.assertEquals("/", fileSeparator);
  }

  private void mockFileRead(String gcsFileName, Instant instant) throws IOException {
    // Any request to open gets a new bogus channel
    Path tempPath = Files.createTempFile("timestamp", ".txt");
    Mockito.when(mockGcsUtil.open(GcsPath.fromUri(gcsFileName)))
        .then(
            (Answer<SeekableByteChannel>)
                invocation ->
                    FileChannel.open(
                        Files.write(tempPath, instant.toString().getBytes(StandardCharsets.UTF_8)),
                        StandardOpenOption.READ,
                        StandardOpenOption.DELETE_ON_CLOSE));
  }

  private StorageObject createStorageObject(String gcsFilename, long fileSize) {
    GcsPath gcsPath = GcsPath.fromUri(gcsFilename);
    // Google APIs will use null for empty files.
    @Nullable BigInteger size = (fileSize == 0) ? null : BigInteger.valueOf(fileSize);
    return new StorageObject()
        .setBucket(gcsPath.getBucket())
        .setName(gcsPath.getObject())
        .setSize(size);
  }

  @Test
  public void testGetAllChildDirectoriesOneLevelDeep() throws IOException {
    Objects modelObjects = new Objects();
    List<StorageObject> items = new ArrayList<>();
    // Files within the directory
    items.add(
        createStorageObject(
            "gs://testbucket/testdirectory/Patient/patient.parquet", 1L /* fileSize */));
    items.add(
        createStorageObject(
            "gs://testbucket/testdirectory/Observation/observation.parquet", 2L /* fileSize */));
    // This is not returned in this case of GCS because there is no files right "under" TEST1.
    // Note in GCS we do not have "directories", we are just simulating them by `/` separators.
    items.add(
        createStorageObject(
            "gs://testbucket/testdirectory/TEST1/TEST2/file.txt", 2L /* fileSize */));
    modelObjects.setItems(items);

    Mockito.when(
            mockGcsUtil.listObjects(
                Mockito.eq("testbucket"), Mockito.anyString(), Mockito.isNull()))
        .thenReturn(modelObjects);

    Set<ResourceId> childDirectories =
        DwhFiles.getAllChildDirectories("gs://testbucket/testdirectory");

    assertThat(childDirectories.size(), equalTo(2));
    assertThat(
        childDirectories.contains(
            FileSystems.matchNewResource("gs://testbucket/testdirectory/Patient", true)),
        equalTo(true));
    assertThat(
        childDirectories.contains(
            FileSystems.matchNewResource("gs://testbucket/testdirectory/Observation", true)),
        equalTo(true));
  }
}
