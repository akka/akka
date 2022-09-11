/*
 * Copyright (C) 2014-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import akka.NotUsed
import akka.actor.ActorSystem
import akka.remote.artery.BenchTestSource
import akka.remote.artery.LatchSink
import akka.stream.impl.fusing.MapAsyncUnordered
import akka.stream.impl.fusing.MapAsyncUnordered1
import akka.stream.scaladsl._
import akka.stream.testkit.scaladsl.StreamTestKit
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._

object MapAsyncOneBenchmark {
  final val OperationsPerInvocation = 100000
}

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class MapAsyncOneBenchmark {
  import MapAsyncBenchmark._

  val config = ConfigFactory.parseString("""
    akka.actor.default-dispatcher {
      executor = "fork-join-executor"
      fork-join-executor {
        parallelism-factor = 1
      }
    }
    """)

  implicit val system: ActorSystem = ActorSystem("MapAsyncBenchmark", config)
  import system.dispatcher

  var testSource: Source[java.lang.Integer, NotUsed] = _
  @Param(Array("false", "true"))
  var spawn = false

  @Setup
  def setup(): Unit = {
    // eager init of materializer
    SystemMaterializer(system).materializer
    testSource = Source.fromGraph(new BenchTestSource(OperationsPerInvocation))
  }

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }

  @Benchmark
  @OperationsPerInvocation(OperationsPerInvocation)
  def mapAsyncUnordered(): Unit = {
    val latch = new CountDownLatch(1)

    testSource
      .via(MapAsyncUnordered(1, (elem => if (spawn) Future(elem) else Future.successful(elem))))
      .runWith(new LatchSink(OperationsPerInvocation, latch))

    awaitLatch(latch)
  }

  @Benchmark
  @OperationsPerInvocation(OperationsPerInvocation)
  def mapAsyncUnordered1(): Unit = {
    val latch = new CountDownLatch(1)

    testSource
      .via(MapAsyncUnordered1((elem => if (spawn) Future(elem) else Future.successful(elem))))
      .runWith(new LatchSink(OperationsPerInvocation, latch))

    awaitLatch(latch)
  }

  private def awaitLatch(latch: CountDownLatch): Unit = {
    if (!latch.await(30, TimeUnit.SECONDS)) {
      StreamTestKit.printDebugDump(SystemMaterializer(system).materializer.supervisor)
      throw new RuntimeException("Latch didn't complete in time")
    }
  }

}
