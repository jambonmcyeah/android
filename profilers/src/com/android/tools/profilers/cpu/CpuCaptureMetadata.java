/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tools.profilers.cpu;

import org.jetbrains.annotations.NotNull;

/**
 * Metadata of a {@link CpuCapture}, such as capture duration, parsing time, profiler type, etc.
 */
public class CpuCaptureMetadata {

  public enum CaptureStatus {
    /** Capture finished successfully. */
    SUCCESS,
    /** There was a failure when trying to stop the capture. */
    STOP_CAPTURING_FAILURE,
    /** There was a failure when trying to parse the capture. */
    PARSING_FAILURE,
    /** User aborted parsing the trace after being notified it was too large. */
    USER_ABORTED_PARSING
  }

  /**
   * Whether the capture + parsing finished successfully or if there was an error in the capturing or parsing steps.
   */
  private CaptureStatus myStatus;

  /**
   * Duration (in milliseconds) of the capture, from the time user pressed "Start recording" to the time they pressed "Stop".
   * If {@link #myStatus} is {@link CaptureStatus#SUCCESS}, the duration is calculated from the capture itself, from the precise start/stop
   * timestamps. Otherwise, the duration is actually an estimate as it's calculated by checking the device time when the user clicks start
   * and when they click stop.
   */
  private long myCaptureDurationMs;

  /**
   * Duration (in milliseconds) from the first trace data timestamp to the last one.
   */
  private long myRecordDurationMs;

  /**
   * Size (in bytes) of the trace file parsed into capture.
   */
  private int myTraceFileSizeBytes;

  /**
   * How much time (in milliseconds) taken to parse the trace file.
   */
  private long myParsingTimeMs;

  /**
   * {@link ProfilingConfiguration} used to start the capture.
   */
  private @NotNull ProfilingConfiguration myProfilingConfiguration;

  public CpuCaptureMetadata(@NotNull ProfilingConfiguration configuration) {
    myProfilingConfiguration = configuration;
  }

  public CaptureStatus getStatus() {
    return myStatus;
  }

  public void setStatus(CaptureStatus status) {
    myStatus = status;
  }

  public int getTraceFileSizeBytes() {
    return myTraceFileSizeBytes;
  }

  public void setTraceFileSizeBytes(int traceFileSizeBytes) {
    myTraceFileSizeBytes = traceFileSizeBytes;
  }

  public long getCaptureDurationMs() {
    return myCaptureDurationMs;
  }

  public void setCaptureDurationMs(long captureDurationMs) {
    myCaptureDurationMs = captureDurationMs;
  }

  public long getParsingTimeMs() {
    return myParsingTimeMs;
  }

  public void setParsingTimeMs(long parsingTimeMs) {
    myParsingTimeMs = parsingTimeMs;
  }

  public long getRecordDurationMs() {
    return myRecordDurationMs;
  }

  public void setRecordDurationMs(long recordDurationMs) {
    myRecordDurationMs = recordDurationMs;
  }

  @NotNull
  public ProfilingConfiguration getProfilingConfiguration() {
    return myProfilingConfiguration;
  }
}
