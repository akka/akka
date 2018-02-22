/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.aeron;

import akka.event.NoLogging;
import io.aeron.CncFileDescriptor;
import org.agrona.DirectBuffer;
import org.agrona.IoUtil;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.errors.ErrorLogReader;

import akka.event.LoggingAdapter;
import akka.util.Helpers;

import java.io.File;
import java.nio.MappedByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Application to print out errors recorded in the command-and-control (cnc) file is maintained by media driver in shared
 * memory. This application reads the cnc file and prints the distinct errors. Layout of the cnc file is described in
 * {@link CncFileDescriptor}.
 */
public class AeronErrorLog
{
    final MappedByteBuffer cncByteBuffer;
    final AtomicBuffer buffer;

    @Deprecated
    public AeronErrorLog(File cncFile)
    {
      this(cncFile, NoLogging.getInstance());
    }

    public AeronErrorLog(File cncFile, LoggingAdapter log)
    {
      cncByteBuffer = IoUtil.mapExistingFile(cncFile, "cnc");
      final DirectBuffer cncMetaDataBuffer = CncFileDescriptor.createMetaDataBuffer(cncByteBuffer);
      final int cncVersion = cncMetaDataBuffer.getInt(CncFileDescriptor.cncVersionOffset(0));
      buffer = CncFileDescriptor.createErrorLogBuffer(cncByteBuffer, cncMetaDataBuffer);

      if (CncFileDescriptor.CNC_VERSION != cncVersion)
      {
        log.warning("Aeron CnC version mismatch: compiled version = {}, file version = {}",
          CncFileDescriptor.CNC_VERSION, cncVersion);
      }
    }

    public long logErrors(LoggingAdapter log, long sinceTimestamp)
    {
        // using AtomicLong because access from lambda, not because of concurrency
        final AtomicLong lastTimestamp = new AtomicLong(sinceTimestamp);

        ErrorLogReader.read(
            buffer,
            (observationCount, firstObservationTimestamp, lastObservationTimestamp, encodedException) -> {
                  log.error(String.format(
                      "Aeron error: %d observations from %s to %s for:%n %s",
                      observationCount,
                      Helpers.timestamp(firstObservationTimestamp),
                      Helpers.timestamp(lastObservationTimestamp),
                      encodedException));
                  lastTimestamp.set(Math.max(lastTimestamp.get(), lastObservationTimestamp));
                }, sinceTimestamp);
        return lastTimestamp.get();
    }

    public void close() {
        IoUtil.unmap(cncByteBuffer);
    }
}
