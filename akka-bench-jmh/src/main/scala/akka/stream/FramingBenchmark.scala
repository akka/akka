/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import java.util.concurrent.{Semaphore, TimeUnit}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Framing, Sink, Source}
import akka.util.ByteString
import com.typesafe.config.{Config, ConfigFactory}
import org.openjdk.jmh.annotations._
import org.reactivestreams.{Publisher, Subscriber, Subscription}

import scala.concurrent.Await
import scala.concurrent.duration._

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class FramingBenchmark {

  val config: Config = ConfigFactory.parseString(
    """
      akka {
        log-config-on-start = off
        log-dead-letters-during-shutdown = off
        stdout-loglevel = "OFF"
        loglevel = "OFF"
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
      }""".stripMargin
  ).withFallback(ConfigFactory.load())

  implicit val system: ActorSystem = ActorSystem("test", config)

  var materializer: ActorMaterializer = _

  // Safe to be benchmark scoped because the flows we construct in this bench are stateless
  var flow: Source[ByteString, NotUsed] = _

  @Param(Array("32", "64", "128"))
  var framePerSeq = 0

  @Setup
  def setup(): Unit = {
    materializer = ActorMaterializer()

    val frame = ByteString(List.range(0, framePerSeq, 1).map(_ => "a" * 128 + "\n").mkString)

    // Important to use a synchronous, zero overhead source, otherwise the slowness of the source
    // might bias the benchmark, since the stream always adjusts the rate to the slowest stage.
    val syncTestPublisher = new Publisher[ByteString] {
      override def subscribe(s: Subscriber[_ >: ByteString]): Unit = {
        val sub: Subscription = new Subscription {
          var counter = 0 // Piggyback on caller thread, no need for volatile

          override def request(n: Long): Unit = {
            var i = n
            while (i > 0) {
              s.onNext(frame)
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

    flow = Source.fromPublisher(syncTestPublisher)
      .via(Framing.delimiter(ByteString("\n"), Int.MaxValue))
  }

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }

  @Benchmark
  @OperationsPerInvocation(100000)
  def framing(): Unit = {
    val lock = new Semaphore(1)
    lock.acquire()
    flow.runWith(Sink.onComplete(_ ⇒ lock.release()))(materializer)
    lock.acquire()
  }

}
