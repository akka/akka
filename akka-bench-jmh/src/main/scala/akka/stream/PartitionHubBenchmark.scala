/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream

import java.util.concurrent.TimeUnit
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl._
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._
import java.util.concurrent.Semaphore
import scala.util.Success
import akka.stream.impl.fusing.GraphStages
import org.reactivestreams._
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.remote.artery.BenchTestSource
import java.util.concurrent.CountDownLatch
import akka.remote.artery.LatchSink
import akka.stream.impl.PhasedFusingActorMaterializer
import akka.testkit.TestProbe
import akka.stream.impl.StreamSupervisor
import akka.stream.scaladsl.PartitionHub
import akka.remote.artery.FixedSizePartitionHub

object PartitionHubBenchmark {
  final val OperationsPerInvocation = 100000
}

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class PartitionHubBenchmark {
  import PartitionHubBenchmark._

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

  implicit val system = ActorSystem("PartitionHubBenchmark", config)

  var materializer: ActorMaterializer = _

  @Param(Array("1", "5", "10"))
  var NumberOfStreams = 0

  @Param(Array("256"))
  var BufferSize = 0

  var testSource: Source[java.lang.Integer, NotUsed] = _

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
  def partition(): Unit = {
    val N = OperationsPerInvocation
    val latch = new CountDownLatch(NumberOfStreams)

    val source = testSource
      .runWith(PartitionHub.sink[java.lang.Integer](
        (size, elem) => elem.intValue % NumberOfStreams,
        startAfterNbrOfConsumers = NumberOfStreams, bufferSize = BufferSize
      ))(materializer)

    for (_ <- 0 until NumberOfStreams)
      source.runWith(new LatchSink(N / NumberOfStreams, latch))(materializer)

    if (!latch.await(30, TimeUnit.SECONDS)) {
      dumpMaterializer()
      throw new RuntimeException("Latch didn't complete in time")
    }
  }

  @Benchmark
  @OperationsPerInvocation(OperationsPerInvocation)
  def arteryLanes(): Unit = {
    val N = OperationsPerInvocation
    val latch = new CountDownLatch(NumberOfStreams)

    val source = testSource
      .runWith(
        Sink.fromGraph(new FixedSizePartitionHub(
          _.intValue % NumberOfStreams,
          lanes = NumberOfStreams, bufferSize = BufferSize
        ))
      )(materializer)

    for (_ <- 0 until NumberOfStreams)
      source.runWith(new LatchSink(N / NumberOfStreams, latch))(materializer)

    if (!latch.await(30, TimeUnit.SECONDS)) {
      dumpMaterializer()
      throw new RuntimeException("Latch didn't complete in time")
    }
  }

  @Benchmark
  @OperationsPerInvocation(OperationsPerInvocation)
  def broadcastFilter(): Unit = {
    // FIXME this can be removed, just for baseline comparison
    val N = OperationsPerInvocation
    val latch = new CountDownLatch(NumberOfStreams)

    val (firstElem, source) = Source.maybe[java.lang.Integer].concat(testSource)
      .toMat(BroadcastHub.sink[java.lang.Integer](bufferSize = BufferSize))(Keep.both).run()(materializer)

    for (n <- 0 until NumberOfStreams)
      source
        .filter(_.intValue % NumberOfStreams == n)
        .runWith(new LatchSink(N / NumberOfStreams - 1000, latch))(materializer)

    //    Thread.sleep(100)
    firstElem.success(Some(0))

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
