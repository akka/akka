/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import java.io.OutputStream
import java.util.concurrent.TimeUnit

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Keep, Sink, StreamConverters }
import org.openjdk.jmh.annotations.TearDown

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import org.openjdk.jmh.annotations._

object OutputStreamSourceStageBenchmark {
  final val WritesPerBench = 10000
}
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class OutputStreamSourceStageBenchmark {
  import OutputStreamSourceStageBenchmark.WritesPerBench
  implicit val system = ActorSystem("OutputStreamSourceStageBenchmark")
  implicit val materializer = ActorMaterializer()

  private val bytes: Array[Byte] = Array.emptyByteArray

  private var os: OutputStream = _
  private var done: Future[Done] = _

  @Benchmark
  @OperationsPerInvocation(WritesPerBench)
  def consumeWrites(): Unit = {
    val (os, done) = StreamConverters.asOutputStream()
      .toMat(Sink.ignore)(Keep.both)
      .run()
    new Thread(new Runnable {
      def run(): Unit = {
        var counter = 0
        while (counter > WritesPerBench) {
          os.write(bytes)
          counter += 1
        }
        os.close()
      }
    }).start()
    Await.result(done, 30.seconds)
  }

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }

}
