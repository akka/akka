/*
 * Copyright 2014 - 2016 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package akka.remote.artery;

import io.aeron.CncFileDescriptor;
import org.agrona.DirectBuffer;
import org.agrona.IoUtil;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.errors.ErrorLogReader;

import akka.event.LoggingAdapter;

import java.io.File;
import java.nio.MappedByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Application to print out errors recorded in the command-and-control (cnc) file is maintained by media driver in shared
 * memory. This application reads the cnc file and prints the distinct errors. Layout of the cnc file is described in
 * {@link CncFileDescriptor}.
 */
public class AeronErrorLog
{
    private final File cncFile;
    final MappedByteBuffer cncByteBuffer;
    final DirectBuffer cncMetaDataBuffer;
    final int cncVersion;
    final AtomicBuffer buffer;
    final SimpleDateFormat dateFormat;

    public AeronErrorLog(File cncFile)
    {
      this.cncFile = cncFile;
      cncByteBuffer = IoUtil.mapExistingFile(cncFile, "cnc");
      cncMetaDataBuffer = CncFileDescriptor.createMetaDataBuffer(cncByteBuffer);
      cncVersion = cncMetaDataBuffer.getInt(CncFileDescriptor.cncVersionOffset(0));
      buffer = CncFileDescriptor.createErrorLogBuffer(cncByteBuffer, cncMetaDataBuffer);
      dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ");


      if (CncFileDescriptor.CNC_VERSION != cncVersion)
      {
          IoUtil.unmap(cncByteBuffer);
          throw new IllegalStateException("CNC version not supported: file version=" + cncVersion);
      }
    }

    public long logErrors(LoggingAdapter log, long sinceTimestamp)
    {
        // using AtomicLong because access from lambda, not because of currency
        final AtomicLong lastTimestamp = new AtomicLong(sinceTimestamp);

        ErrorLogReader.read(
            buffer,
            (observationCount, firstObservationTimestamp, lastObservationTimestamp, encodedException) -> {
                  log.error(String.format(
                      "Aeron error: %d observations from %s to %s for:%n %s",
                      observationCount,
                      dateFormat.format(new Date(firstObservationTimestamp)),
                      dateFormat.format(new Date(lastObservationTimestamp)),
                      encodedException));
                  lastTimestamp.set(Math.max(lastTimestamp.get(), lastObservationTimestamp));
                }, sinceTimestamp);
        return lastTimestamp.get();
    }

    public void close() {
        IoUtil.unmap(cncByteBuffer);
    }
}
