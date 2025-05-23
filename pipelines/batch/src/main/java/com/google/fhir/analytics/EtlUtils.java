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

import com.google.common.base.Strings;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.apache.beam.sdk.metrics.MetricNameFilter;
import org.apache.beam.sdk.metrics.MetricQueryResults;
import org.apache.beam.sdk.metrics.MetricResult;
import org.apache.beam.sdk.metrics.MetricResults;
import org.apache.beam.sdk.metrics.MetricsFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class EtlUtils {

  /** Total number of pipelines that can be executed in parallel at a given time. */
  public static final int NO_OF_PARALLEL_PIPELINES = 2;

  private static final Logger log = LoggerFactory.getLogger(EtlUtils.class);

  static MetricQueryResults getMetrics(MetricResults metricResults) {
    return metricResults.queryMetrics(
        MetricsFilter.builder()
            .addNameFilter(MetricNameFilter.inNamespace(MetricsConstants.METRICS_NAMESPACE))
            .build());
  }

  static void logMetrics(MetricResults metricResults) {
    MetricQueryResults metrics = getMetrics(metricResults);
    for (MetricResult<Long> counter : metrics.getCounters()) {
      log.info(
          String.format("Pipeline counter %s : %s", counter.getName(), counter.getAttempted()));
    }
  }

  /**
   * Runs the given `pipelines` and if the output is to a Parquet DWH, also writes a timestamp file
   * indicating when the pipelines were started. This is useful for future incremental runs.
   *
   * @param pipelines the pipelines to be executed
   * @param options the {@link FhirEtlOptions} to be used by the {@code pipelines}
   * @return the result from running the pipelines.
   * @throws IOException if writing the timestamp file fails.
   */
  static List<PipelineResult> runMultiplePipelinesWithTimestamp(
      List<Pipeline> pipelines, FhirEtlOptions options) throws IOException {
    log.info("Starting another run with flags: " + options);
    String dwhRoot = options.getOutputParquetPath();
    String viewRoot = options.getOutputParquetViewPath();
    if (!Strings.isNullOrEmpty(dwhRoot)) {
      // TODO write pipeline options too such that it  can be validated for incremental runs.
      DwhFiles.writeTimestampFile(dwhRoot, DwhFiles.TIMESTAMP_FILE_START);
    }
    if (!Strings.isNullOrEmpty(viewRoot)) {
      DwhFiles.writeTimestampFile(viewRoot, DwhFiles.TIMESTAMP_FILE_START);
    }
    List<PipelineResult> pipelineResults = runMultiplePipelines(pipelines);
    if (!Strings.isNullOrEmpty(viewRoot)) {
      DwhFiles.writeTimestampFile(viewRoot, DwhFiles.TIMESTAMP_FILE_END);
    }
    if (!Strings.isNullOrEmpty(dwhRoot)) {
      DwhFiles.writeTimestampFile(dwhRoot, DwhFiles.TIMESTAMP_FILE_END);
    }
    return pipelineResults;
  }

  /** Similar to {@link #runMultiplePipelinesWithTimestamp} but for the merge pipeline. */
  static List<PipelineResult> runMultipleMergerPipelinesWithTimestamp(
      List<Pipeline> pipelines, ParquetMergerOptions options) throws IOException {
    log.info("Starting another merger-run with flags: " + options);
    mergeWithLatestTimestamp(
        options.getDwh1(),
        options.getDwh2(),
        options.getMergedDwh(),
        DwhFiles.TIMESTAMP_FILE_START);
    // Transaction file exists for Batch Export mode, merge the timestamp file in this case
    if (DwhFiles.doesTimestampFileExist(
        options.getDwh1(), DwhFiles.TIMESTAMP_FILE_BULK_TRANSACTION_TIME)) {
      mergeWithLatestTimestamp(
          options.getDwh1(),
          options.getDwh2(),
          options.getMergedDwh(),
          DwhFiles.TIMESTAMP_FILE_BULK_TRANSACTION_TIME);
    }
    List<PipelineResult> pipelineResults = runMultiplePipelines(pipelines);
    DwhFiles.writeTimestampFile(options.getMergedDwh(), DwhFiles.TIMESTAMP_FILE_END);
    if (!Strings.isNullOrEmpty(options.getViewDefinitionsDir())) {
      ResourceId viewPath = DwhFiles.getLatestViewsPath(options.getMergedDwh());
      if (viewPath != null) {
        // Note in this function, we cannot write the start timestamp file under the VIEWS path
        // because the exact value of that path is determined in ParquetMerger. Hence, writing
        // the start timestamp is also done there.
        DwhFiles.writeTimestampFile(viewPath.toString(), DwhFiles.TIMESTAMP_FILE_END);
      }
    }
    return pipelineResults;
  }

  private static void mergeWithLatestTimestamp(
      String dwhRoot1, String dwhRoot2, String mergedDwhRoot, String fileName) throws IOException {
    Instant instant1 = DwhFiles.readTimestampFile(dwhRoot1, fileName);
    Instant instant2 = DwhFiles.readTimestampFile(dwhRoot2, fileName);
    Instant mergedInstant = (instant1.compareTo(instant2) > 0) ? instant1 : instant2;
    DwhFiles.writeTimestampFile(mergedDwhRoot, mergedInstant, fileName);
  }

  /**
   * Execute the given pipelines and return the list of pipeline results. The pipelines will be
   * executed using an {@link Executor} pool. The number of threads in the pool is limited to low
   * value equal to {@value NO_OF_PARALLEL_PIPELINES} because each execution of the pipeline
   * reserves a certain amount of FLINK off-heap memory and with high parallelism it can block a
   * large amount of memory.
   *
   * @param pipelines the pipelines to be executed
   * @return the list of results of the pipelines
   */
  private static List<PipelineResult> runMultiplePipelines(List<Pipeline> pipelines) {
    List<PipelineResult> pipelineResults = new ArrayList<>();
    ExecutorService executor = null;
    try {
      executor = Executors.newFixedThreadPool(EtlUtils.NO_OF_PARALLEL_PIPELINES);
      List<CompletableFuture<PipelineResult>> completableFutures = new ArrayList<>();
      for (Pipeline pipeline : pipelines) {
        CompletableFuture<PipelineResult> completableFuture =
            CompletableFuture.supplyAsync(new PipelineSupplier(pipeline), executor);
        completableFutures.add(completableFuture);
      }
      CompletableFuture<Void> finalCompletableFuture =
          CompletableFuture.allOf(completableFutures.toArray(CompletableFuture[]::new));
      try {
        // Waits for all the pipeline executions to complete
        finalCompletableFuture.get();
        for (CompletableFuture completableFuture : completableFutures) {
          pipelineResults.add((PipelineResult) completableFuture.get());
        }
      } catch (InterruptedException e) {
        log.error(
            "Caught InterruptedException; resetting interrupt flag and throwing "
                + "RuntimeException! ",
            e);
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      } catch (ExecutionException e) {
        log.error("Error while executing the pipelines", e);
        throw new RuntimeException(e);
      }
    } finally {
      if (executor != null) {
        executor.shutdown();
      }
    }
    return pipelineResults;
  }

  /** Supplier implementation which executes the given pipeline and returns the result. */
  static class PipelineSupplier implements Supplier<PipelineResult> {
    private Pipeline pipeline;

    public PipelineSupplier(Pipeline pipeline) {
      this.pipeline = pipeline;
    }

    @Override
    public PipelineResult get() {
      // Note that with even with FlinkRunner, in the "local" mode the next call is blocking.
      PipelineResult result = pipeline.run();
      result.waitUntilFinish();
      EtlUtils.logMetrics(result.metrics());
      return result;
    }
  }
}
