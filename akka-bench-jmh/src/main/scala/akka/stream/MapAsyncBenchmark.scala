/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._

import akka.NotUsed
import akka.actor.ActorSystem
import akka.remote.artery.BenchTestSource
import akka.remote.artery.LatchSink
import akka.stream.impl.PhasedFusingActorMaterializer
import akka.stream.impl.StreamSupervisor
import akka.stream.scaladsl._
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

object MapAsyncBenchmark {
  final val OperationsPerInvocation = 100000
}

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class MapAsyncBenchmark {
  import MapAsyncBenchmark._

  val config = ConfigFactory.parseString(
    """
    akka.actor.default-dispatcher {
      executor = "fork-join-executor"
      fork-join-executor {
        parallelism-factor = 1
      }
    }
    """
  )

  implicit val system = ActorSystem("MapAsyncBenchmark", config)
  import system.dispatcher

  var materializer: ActorMaterializer = _

  var testSource: Source[java.lang.Integer, NotUsed] = _

  @Param(Array("1", "4"))
  var parallelism = 0

  @Param(Array("false", "true"))
  var spawn = false

  @Setup
  def setup(): Unit = {
    val settings = ActorMaterializerSettings(system)
    materializer = ActorMaterializer(settings)

    testSource = Source.fromGraph(new BenchTestSource(OperationsPerInvocation))
  }

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }

  @Benchmark
  @OperationsPerInvocation(OperationsPerInvocation)
  def mapAsync(): Unit = {
    val latch = new CountDownLatch(1)

    testSource
      .mapAsync(parallelism)(elem ⇒ if (spawn) Future(elem) else Future.successful(elem))
      .runWith(new LatchSink(OperationsPerInvocation, latch))(materializer)

    awaitLatch(latch)
  }

  @Benchmark
  @OperationsPerInvocation(OperationsPerInvocation)
  def mapAsyncUnordered(): Unit = {
    val latch = new CountDownLatch(1)

    testSource
      .mapAsyncUnordered(parallelism)(elem ⇒ if (spawn) Future(elem) else Future.successful(elem))
      .runWith(new LatchSink(OperationsPerInvocation, latch))(materializer)

    awaitLatch(latch)
  }

  private def awaitLatch(latch: CountDownLatch): Unit = {
    if (!latch.await(30, TimeUnit.SECONDS)) {
      dumpMaterializer()
      throw new RuntimeException("Latch didn't complete in time")
    }
  }

  private def dumpMaterializer(): Unit = {
    materializer match {
      case impl: PhasedFusingActorMaterializer ⇒
        val probe = TestProbe()(system)
        impl.supervisor.tell(StreamSupervisor.GetChildren, probe.ref)
        val children = probe.expectMsgType[StreamSupervisor.Children].children
        children.foreach(_ ! StreamSupervisor.PrintDebugDump)
    }
  }

}
