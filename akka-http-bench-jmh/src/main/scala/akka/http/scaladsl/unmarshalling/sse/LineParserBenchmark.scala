package akka.http.scaladsl.unmarshalling.sse

import java.nio.file.{ Files, Path }
import java.util.concurrent.TimeUnit

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ FileIO, Keep, RunnableGraph, Sink, Source }
import akka.util.ByteString
import org.openjdk.jmh.annotations._

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
class LineParserBenchmark {
  implicit val system: ActorSystem = ActorSystem("line-parser-benchmark")
  implicit val mat: ActorMaterializer = ActorMaterializer()

  // @formatter:off
  @Param(Array(
    "1024",     // around 1   KB event
    "130945",   // around 128 KB event
    "523777",   // around 512 KB event
    "1129129"   // around 1   MB event
  ))
  var lineSize = 0
  // @formatter:on

  // checking different chunk sizes because of chunk size has direct impact on algorithm
  @Param(Array(
    "512",
    "1024",
    "2048",
    "4096",
    "8192"
  ))
  var chunkSize = 0

  lazy val line = ByteString("x" * lineSize + "\n")

  var parserGraph: RunnableGraph[Future[Done]] = _
  var tempFile: Path = _

  @Setup
  def setup(): Unit = {
    tempFile = Files.createTempFile("akka-http-linear-bench", s"-$lineSize")
    val makeSampleFile = Source.single(line)
      .runWith(FileIO.toPath(tempFile))

    Await.result(makeSampleFile, 5 seconds)

    parserGraph = FileIO
      .fromPath(tempFile, chunkSize)
      .via(new LineParser(lineSize + 1))
      .toMat(Sink.ignore)(Keep.right)
  }

  @TearDown
  def tearDown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }

  @TearDown
  def cleanUpFile(): Unit = {
    tempFile.toFile.delete()
  }

  @Benchmark
  def bench_line_parser(): Unit = {
    Await.result(parserGraph.run(), Duration.Inf)
  }
}
