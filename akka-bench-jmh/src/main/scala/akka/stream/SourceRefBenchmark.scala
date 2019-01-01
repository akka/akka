/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import java.util.concurrent.{ Semaphore, TimeUnit }

import akka.actor.ActorSystem
import akka.stream.scaladsl._
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._
import org.reactivestreams._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Success

/*
   Just a brief reference run (3.1 GHz Intel Core i7, MacBook Pro late 2017):
   [info] SourceRefBenchmark.source_ref_100k_elements  thrpt   10  724650.336 ± 233643.256  ops/s
 */
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class SourceRefBenchmark {

  val config = ConfigFactory.parseString(
    """
      akka {
        log-config-on-start = off
        log-dead-letters-during-shutdown = off
        loglevel = "WARNING"
      }""".stripMargin
  ).withFallback(ConfigFactory.load())

  implicit val system = ActorSystem("test", config)

  implicit val materializer: ActorMaterializer = ActorMaterializer()

  final val successMarker = Success(1)
  final val successFailure = Success(new Exception)

  // safe to be benchmark scoped because the flows we construct in this bench are stateless
  var sourceRef: SourceRef[Int] = _

  //  @Param(Array("16", "32", "128"))
  //  var initialInputBufferSize = 0

  @Setup(Level.Invocation)
  def setup(): Unit = {
    val sourcePublisher = new Publisher[Int] {
      override def subscribe(s: Subscriber[_ >: Int]): Unit = {
        val sub = new Subscription {
          var counter = 0 // Piggyback on caller thread, no need for volatile

          override def request(n: Long): Unit = {
            var i = n
            while (i > 0) {
              s.onNext(counter)
              counter += 1
              if (counter == 100000) {
                s.onComplete()
                return
              }
              i -= 1
            }
          }

          override def cancel(): Unit = ()
        }

        s.onSubscribe(sub)
      }
    }

    sourceRef = Await.result(Source.fromPublisher(sourcePublisher).runWith(StreamRefs.sourceRef()), 10.seconds)
  }

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }

  @Benchmark
  @OperationsPerInvocation(100000)
  def source_ref_100k_elements(): Unit = {
    val lock = new Semaphore(1) // todo rethink what is the most lightweight way to await for a streams completion
    lock.acquire()

    sourceRef.source.runWith(Sink.onComplete(_ ⇒ lock.release()))

    lock.acquire()
  }

}
