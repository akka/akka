/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.io

import java.io.{ FileInputStream, File }
import java.util.concurrent.TimeUnit
import akka.{ Done, NotUsed }
import akka.actor.ActorSystem
import akka.stream.{ Attributes, ActorMaterializer }
import akka.stream.scaladsl._
import akka.util.ByteString
import org.openjdk.jmh.annotations._
import scala.concurrent.duration._
import scala.concurrent.{ Promise, Await, Future }
import akka.stream.IOResult

/**
 * Benchmark                         (bufSize)  Mode  Cnt    Score    Error  Units
 * FileSourcesBenchmark.fileChannel       2048  avgt  100  1140.192 ± 55.184  ms/op
 */
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
class FileSourcesBenchmark {

  implicit val system = ActorSystem("file-sources-benchmark")
  implicit val materializer = ActorMaterializer()

  val file: File = {
    val line = ByteString("x" * 2048 + "\n")

    val f = File.createTempFile(getClass.getName, ".bench.tmp")
    f.deleteOnExit()

    val ft = Source.fromIterator(() ⇒ Iterator.continually(line))
      .take(10 * 39062) // adjust as needed
      .runWith(FileIO.toFile(f))
    Await.result(ft, 30.seconds)

    f
  }

  @Param(Array("2048"))
  var bufSize = 0

  var fileChannelSource: Source[ByteString, Future[IOResult]] = _
  var fileInputStreamSource: Source[ByteString, Future[IOResult]] = _
  var ioSourceLinesIterator: Source[ByteString, NotUsed] = _

  @Setup
  def setup():Unit = {
    fileChannelSource = FileIO.fromFile(file, bufSize)
    fileInputStreamSource = StreamConverters.fromInputStream(() ⇒ new FileInputStream(file), bufSize)
    ioSourceLinesIterator = Source.fromIterator(() ⇒ scala.io.Source.fromFile(file).getLines()).map(ByteString(_))
  }

  @TearDown
  def teardown(): Unit = {
    file.delete()
  }

  @TearDown
  def shutdown():Unit = {
    Await.result(system.terminate(), Duration.Inf)
  }

  @Benchmark
  def fileChannel():Unit = {
    val h = fileChannelSource.to(Sink.ignore).run()

    Await.result(h, 30.seconds)
  }

  @Benchmark
  def fileChannel_noReadAhead():Unit = {
    val h = fileChannelSource.withAttributes(Attributes.inputBuffer(1, 1)).to(Sink.ignore).run()

    Await.result(h, 30.seconds)
  }

  @Benchmark
  def inputStream():Unit = {
    val h = fileInputStreamSource.to(Sink.ignore).run()

    Await.result(h, 30.seconds)
  }

  /*
   * The previous status quo was very slow:
   * Benchmark                                         Mode  Cnt     Score      Error  Units
   * FileSourcesBenchmark.naive_ioSourceLinesIterator  avgt   20  7067.944 ± 1341.847  ms/op
   */
  @Benchmark
  def naive_ioSourceLinesIterator():Unit = {
    val p = Promise[Done]()
    ioSourceLinesIterator.to(Sink.onComplete(p.complete(_))).run()

    Await.result(p.future, 30.seconds)
  }

}
