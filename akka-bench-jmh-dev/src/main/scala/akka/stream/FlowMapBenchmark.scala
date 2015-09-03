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

  var materializer: ActorMaterializer = _


  // manual, and not via @Param, because we want @OperationsPerInvocation on our tests
  final val data100k = (1 to 100000).toVector

  final val successMarker = Success(1)
  final val successFailure = Success(new Exception)

  // safe to be benchmark scoped because the flows we construct in this bench are stateless
  var flow: Source[Int, Unit] = _

  @Param(Array("2", "8")) // todo
  val initialInputBufferSize = 0

  @Param(Array("1", "5", "10"))
  val numberOfMapOps = 0

  @Setup
  def setup() {
    val settings = ActorMaterializerSettings(system)
      .withInputBuffer(initialInputBufferSize, 16)

    materializer = ActorMaterializer(settings)

    flow = mkMaps(Source(data100k), numberOfMapOps)(identity)
  }

  @TearDown
  def shutdown() {
    system.shutdown()
    system.awaitTermination()
  }

  @Benchmark
  @OperationsPerInvocation(100000)
  def flow_map_100k_elements() {
    val lock = new Lock() // todo rethink what is the most lightweight way to await for a streams completion
    lock.acquire()

    flow.runWith(Sink.onComplete(_ ⇒ lock.release()))(materializer)

    lock.acquire()
  }

  // source setup
  private def mkMaps[O, Mat](source: Source[O, Mat], count: Int)(op: O ⇒ O): Source[O, Mat] = {
    var f = source
    for (i ← 1 to count)
      f = f.map(op)
    f
  }


}
