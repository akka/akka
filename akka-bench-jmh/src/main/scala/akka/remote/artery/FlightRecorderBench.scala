/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.{ OperationsPerInvocation, _ }

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class FlightRecorderBench {

  @Param(Array("1", "5", "10"))
  var writers: Int = 0

  val Writes = 10000000

  private var file: File = _
  private var fileChannel: FileChannel = _
  private var recorder: FlightRecorder = _

  @Setup
  def setup(): Unit = {
    file = File.createTempFile("akka-flightrecorder", "dat")
    file.deleteOnExit()
    fileChannel = FileChannel.open(file.toPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ)
    recorder = new FlightRecorder(fileChannel)
  }

  @TearDown
  def shutdown(): Unit = {
    fileChannel.force(false)
    recorder.close()
    fileChannel.close()
    file.delete()
  }

  @Benchmark
  @OperationsPerInvocation(10000000)
  def flight_recorder_writes(): Unit = {
    val latch = new CountDownLatch(writers)
    (1 to writers).foreach { _ =>
      val sink = recorder.createEventSink()
      new Thread {
        override def run(): Unit = {
          var i = Writes
          while (i > 0) {
            sink.hiFreq(16, 16)
            i -= 1
          }
          latch.countDown()
        }
      }.run()
    }

    latch.await()
  }

}
