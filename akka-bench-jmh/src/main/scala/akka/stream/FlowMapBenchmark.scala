/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.stream

import org.openjdk.jmh.annotations._
import scala.collection.immutable
import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.stream.testkit._
import com.typesafe.config.ConfigFactory
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.concurrent.{ Lock, Promise, duration, Await }
import scala.concurrent.duration.Duration
import scala.util.Success
import akka.stream.impl.SynchronousProducerFromIterable

object FlowMapBenchmark {
  class IntIterable(upTo: Int) extends immutable.Iterable[Int] {
    override def iterator: Iterator[Int] = new Iterator[Int] {
      var n = 0
      override def hasNext: Boolean = n < upTo
      override def next(): Int = {
        n += 1
        n
      }
    }
  }
}

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class FlowMapBenchmark {
  import FlowMapBenchmark._

  var system: ActorSystem = _

  var materializer: FlowMaterializer = _

  // manual, and not via @Param, because we want @OperationsPerInvocation on our tests
  final val data500k = new IntIterable(500000)

  final val successMarker = Success(1)
  final val successFailure = Success(new Exception)

  // safe to be benchmark scoped because the flows we construct in this bench are stateless
  var flow: Flow[Int] = _

  @Param(Array("1", "3", "5")) // "10", "15"
  val numberOfMapOps = 3

  @Param(Array("16", "32")) // "8", "16", "32"
  val inputBufferSize = 16

  @Param(Array("4"))
  val dispatcherPoolSize = 4

  @Setup
  def setup() {

    val config = ConfigFactory.parseString(
      s"""
      akka {
        log-config-on-start = off
        log-dead-letters-during-shutdown = off
        loglevel = "WARNING"

        test.stream-dispatcher {
          type = Dispatcher
          executor = "fork-join-executor"
          fork-join-executor {
            parallelism-min = ${dispatcherPoolSize}
            parallelism-max = ${dispatcherPoolSize}
          }
          throughput = 5
        }
        actor.default-mailbox.mailbox-type = akka.dispatch.SingleConsumerOnlyUnboundedMailbox
      }""".stripMargin).withFallback(ConfigFactory.load())

    system = ActorSystem("test", config)

    val settings = MaterializerSettings(
      initialInputBufferSize = inputBufferSize,
      maximumInputBufferSize = inputBufferSize,
      initialFanOutBufferSize = 1,
      maxFanOutBufferSize = 16,
      dispatcher = "akka.test.stream-dispatcher")

    materializer = FlowMaterializer(settings)(system)

    flow = mkMaps(Flow(SynchronousProducerFromIterable(data500k)), numberOfMapOps)(identity)

  }

  @TearDown
  def shutdown() {
    system.shutdown()
    system.awaitTermination()
  }

  @GenerateMicroBenchmark
  @OperationsPerInvocation(500000)
  def flow_map_500k_elements() {
    val lock = new Lock() // todo rethink what is the most lightweight way to await for a streams completion
    lock.acquire()

    flow.onComplete(materializer) { _ => lock.release() }

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
