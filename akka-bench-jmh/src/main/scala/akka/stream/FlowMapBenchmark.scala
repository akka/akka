/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import java.util.concurrent.TimeUnit
import akka.NotUsed
import akka.actor.ActorSystem
import akka.remote.artery.BenchTestSource
import akka.stream.scaladsl._
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._
import java.util.concurrent.Semaphore
import scala.util.Success
import akka.stream.impl.fusing.GraphStages
import scala.concurrent.Await
import scala.concurrent.duration._

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class FlowMapBenchmark {

  val config = ConfigFactory.parseString("""
      akka {
        log-config-on-start = off
        log-dead-letters-during-shutdown = off
        loglevel = "WARNING"

        actor.default-dispatcher {
          #executor = "thread-pool-executor"
          throughput = 1024
        }

        actor.default-mailbox {
          mailbox-type = "akka.dispatch.SingleConsumerOnlyUnboundedMailbox"
        }

        test {
          timefactor =  1.0
          filter-leeway = 3s
          single-expect-default = 3s
          default-timeout = 5s
          calling-thread-dispatcher {
            type = akka.testkit.CallingThreadDispatcherConfigurator
          }
        }
      }""".stripMargin).withFallback(ConfigFactory.load())

  implicit val system = ActorSystem("test", config)

  var materializer: ActorMaterializer = _

  @Param(Array("true", "false"))
  var UseGraphStageIdentity = false

  final val successMarker = Success(1)
  final val successFailure = Success(new Exception)

  // safe to be benchmark scoped because the flows we construct in this bench are stateless
  var flow: Source[java.lang.Integer, NotUsed] = _

  @Param(Array("8", "32", "128"))
  var initialInputBufferSize = 0

  @Param(Array("1", "5", "10"))
  var numberOfMapOps = 0

  @Setup
  def setup(): Unit = {
    val settings = ActorMaterializerSettings(system).withInputBuffer(initialInputBufferSize, initialInputBufferSize)

    materializer = ActorMaterializer(settings)

    flow = mkMaps(Source.fromGraph(new BenchTestSource(100000)), numberOfMapOps) {
      if (UseGraphStageIdentity)
        GraphStages.identity[java.lang.Integer]
      else
        Flow[java.lang.Integer].map(identity)
    }
  }

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }

  @Benchmark
  @OperationsPerInvocation(100000)
  def flow_map_100k_elements(): Unit = {
    val lock = new Semaphore(1) // todo rethink what is the most lightweight way to await for a streams completion
    lock.acquire()

    flow.runWith(Sink.onComplete(_ => lock.release()))(materializer)

    lock.acquire()
  }

  // source setup
  private def mkMaps[O, Mat](source: Source[O, Mat], count: Int)(flow: => Graph[FlowShape[O, O], _]): Source[O, Mat] = {
    var f = source
    for (i <- 1 to count)
      f = f.via(flow)
    f
  }

}
