/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.testkit

import java.nio.file.{ FileSystems, Files, Path }

import akka.remote.RARP
import akka.remote.artery.FlightRecorderReader

/**
 * Provides test framework agnostic methods to dump the artery flight recorder data after a test has completed - you
 * must integrate the logic with the testing tool you use yourself.
 *
 * The flight recorder must be enabled and the flight recorder destination must be an absolute file name so
 * that the akka config can be used to find it. For example you could ensure a unique file per test using
 * something like this in your config:
 * {{{
 *   akka.remote.artery.advanced.flight-recorder {
 *     enabled=on
 *     destination=target/flight-recorder-${UUID.randomUUID().toString}.afr
 *   }
 * }}}
 *
 * You need to hook in dump and deletion of files where it makes sense in your tests. (For example, dump after all tests has
 * run and there was a failure and then delete)
 */
trait FlightRecordingSupport { self: MultiNodeSpec ⇒
  private lazy val arteryEnabled =
    RARP(system).provider.remoteSettings.Artery.Enabled
  private lazy val flightRecorderFile: Path =
    FileSystems.getDefault.getPath(RARP(system).provider.remoteSettings.Artery.Advanced.FlightRecorderDestination)

  /**
   * Delete flight the recorder file if it exists
   */
  final protected def deleteFlightRecorderFile(): Unit = {
    if (arteryEnabled && destinationIsValidForDump() && Files.exists(flightRecorderFile)) {
      Files.delete(flightRecorderFile)
    }
  }

  /**
   * Dump the contents of the flight recorder file to standard output
   */
  final protected def printFlightRecording(): Unit = {
    if (arteryEnabled && destinationIsValidForDump() && Files.exists(flightRecorderFile)) {
      // use stdout/println as we do not know if the system log is alive
      println(s"Flight recorder dump from '$flightRecorderFile':")
      FlightRecorderReader.dumpToStdout(flightRecorderFile)
    }
  }

  private def destinationIsValidForDump() = {
    val path = flightRecorderFile.toString
    path != "" && path.endsWith(".afr")
  }

}
