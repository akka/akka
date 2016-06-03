/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor

import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import scala.concurrent.Await

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@Fork(1)
@Threads(1)
@Warmup(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS, batchSize = 1)
@Measurement(iterations = 20)
class ForkJoinActorBenchmark {
  import ForkJoinActorBenchmark._

  @Param(Array("1", "5"))
  var tpt = 0

  @Param(Array("1", "4"))
  var threads = ""

  implicit var system: ActorSystem = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    system = ActorSystem("ForkJoinActorBenchmark", ConfigFactory.parseString(
      s"""| akka {
        |   log-dead-letters = off
        |   actor {
        |     default-dispatcher {
        |       executor = "fork-join-executor"
        |       fork-join-executor {
        |         parallelism-min = 1
        |         parallelism-factor = $threads
        |         parallelism-max = 64
        |       }
        |       throughput = $tpt
        |     }
        |   }
        | }
      """.stripMargin
    ))
  }

  @TearDown(Level.Trial)
  def shutdown(): Unit = {
    system.terminate()
    Await.ready(system.whenTerminated, 15.seconds)
  }

  @Benchmark
  @Measurement(timeUnit = TimeUnit.MILLISECONDS)
  @OperationsPerInvocation(messages)
  def pingPong(): Unit = {
    val ping = system.actorOf(Props[ForkJoinActorBenchmark.PingPong])
    val pong = system.actorOf(Props[ForkJoinActorBenchmark.PingPong])

    ping.tell(message, pong)

    val p = TestProbe()
    p.watch(ping)
    p.expectTerminated(ping, timeout)
    p.watch(pong)
    p.expectTerminated(pong, timeout)
  }

  @Benchmark
  @Measurement(timeUnit = TimeUnit.MILLISECONDS)
  @OperationsPerInvocation(messages)
  def floodPipe(): Unit = {

    val end = system.actorOf(Props(classOf[ForkJoinActorBenchmark.Pipe], None))
    val middle = system.actorOf(Props(classOf[ForkJoinActorBenchmark.Pipe], Some(end)))
    val penultimate = system.actorOf(Props(classOf[ForkJoinActorBenchmark.Pipe], Some(middle)))
    val beginning = system.actorOf(Props(classOf[ForkJoinActorBenchmark.Pipe], Some(penultimate)))

    val p = TestProbe()
    p.watch(end)

    def send(left: Int): Unit =
      if (left > 0) {
        beginning ! message
        send(left - 1)
      }

    send(messages / 4) // we have 4 actors in the pipeline

    beginning ! stop

    p.expectTerminated(end, timeout)
  }
}

object ForkJoinActorBenchmark {
  final val stop = "stop"
  final val message = "message"
  final val timeout = 15.seconds
  final val messages = 400000
  class Pipe(next: Option[ActorRef]) extends Actor {
    def receive = {
      case m @ `message` =>
        if (next.isDefined) next.get forward m
      case s @ `stop` =>
        context stop self
        if (next.isDefined) next.get forward s
    }
  }
  class PingPong extends Actor {
    var left = messages / 2
    def receive = {
      case `message` =>

        if (left <= 1)
          context stop self

        sender() ! message
        left -= 1
    }
  }
}
