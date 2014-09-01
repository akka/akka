/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.stream

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.scaladsl._
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

import scala.concurrent.Lock
import scala.util.Success

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class FlowMapBenchmark {

  val config = ConfigFactory.parseString(
    """
      akka {
        log-config-on-start = off
        log-dead-letters-during-shutdown = off
        loglevel = "WARNING"

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

  var materializer: FlowMaterializer = _


  // manual, and not via @Param, because we want @OperationsPerInvocation on our tests
  final val data100k = (1 to 100000).toVector

  final val successMarker = Success(1)
  final val successFailure = Success(new Exception)

  // safe to be benchmark scoped because the flows we construct in this bench are stateless
  var flow: Flow[Int] = _

  @Param(Array("2", "8")) // todo
  val initialInputBufferSize = 0

  @Param(Array("1", "5", "10"))
  val numberOfMapOps = 0

  @Setup
  def setup() {
    val settings = MaterializerSettings(system)
      .withInputBuffer(initialInputBufferSize, 16)
      .withFanOutBuffer(1, 16)

    materializer = FlowMaterializer(settings)

    flow = mkMaps(Flow(data100k), numberOfMapOps)(identity)
  }

  @TearDown
  def shutdown() {
    system.shutdown()
    system.awaitTermination()
  }

  @GenerateMicroBenchmark
  @OperationsPerInvocation(100000)
  def flow_map_100k_elements() {
    val lock = new Lock() // todo rethink what is the most lightweight way to await for a streams completion
    lock.acquire()

    flow.onComplete({ _ => lock.release() })(materializer)

    lock.acquire()
  }

  // flow setup
  private def mkMaps[O](flow: Flow[O], count: Int)(op: O ⇒ O): Flow[O] = {
    var f = flow
    for (i ← 1 to count)
      f = f.map(op)
    f
  }


}
