/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.io

import java.nio.file.{ Files, Path }
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.stream.{ ActorMaterializer, IOResult }
import akka.util.ByteString
import org.openjdk.jmh.annotations.{ BenchmarkMode, Scope, State, _ }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@Fork(1)
@Threads(1)
@Warmup(iterations = 5, timeUnit = TimeUnit.SECONDS, batchSize = 1)
@Measurement(iterations = 10, timeUnit = TimeUnit.SECONDS, batchSize = 1)
class FileSourcesScaleBenchmark {

  /**
   * Benchmark                               (bufSize)  Mode  Cnt  Score   Error  Units
   * FileSourcesScaleBenchmark.flatMapMerge       2048  avgt   10  1.587 ± 0.118   s/op
   * FileSourcesScaleBenchmark.mapAsync           2048  avgt   10  0.899 ± 0.103   s/op
   */
  implicit val system = ActorSystem("file-sources-benchmark")
  implicit val materializer = ActorMaterializer()
  val FILES_NUMBER = 40
  val files: Seq[Path] = {
    val line = ByteString("x" * 2048 + "\n")
    (1 to FILES_NUMBER).map(i => {
      val f = Files.createTempFile(getClass.getName, i + ".bench.tmp")

      val ft = Source
        .fromIterator(() => Iterator.continually(line))
        .take(20000) // adjust as needed
        .runWith(FileIO.toPath(f))
      Await.result(ft, 300.seconds)
      f
    })
  }

  @Param(Array("2048"))
  var bufSize = 0

  var fileChannelSource: Seq[Source[ByteString, Future[IOResult]]] = _

  @Setup
  def setup(): Unit = {
    fileChannelSource = files.map(FileIO.fromPath(_, bufSize))
  }

  @TearDown
  def teardown(): Unit = {
    files.foreach(Files.delete)
  }

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), Duration.Inf)
  }

  @Benchmark
  def flatMapMerge(): Unit = {
    val h = Source
      .fromIterator(() => files.iterator)
      .flatMapMerge(FILES_NUMBER, path => FileIO.fromPath(path, bufSize))
      .runWith(Sink.ignore)

    Await.result(h, 300.seconds)
  }

  @Benchmark
  def mapAsync(): Unit = {
    val h = Source
      .fromIterator(() => files.iterator)
      .mapAsync(FILES_NUMBER)(path => FileIO.fromPath(path, bufSize).runWith(Sink.ignore))
      .runWith(Sink.ignore)

    Await.result(h, 300.seconds)
  }

}
