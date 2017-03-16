/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
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

  @Param(Array("1", "5", "10"))
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

    ping.tell(Message, pong)

    val p = TestProbe()
    p.watch(ping)
    p.expectTerminated(ping, timeout)
    p.watch(pong)
    p.expectTerminated(pong, timeout)
  }

  @Benchmark
  @Measurement(timeUnit = TimeUnit.MILLISECONDS)
  @OperationsPerInvocation(totalMessagesLessThanCores)
  def pingPongLessActorsThanCores(): Unit = {
    val pingPongs =
      for {
        i <- 1 to lessThanCoresActorPairs
      } yield {
        val ping = system.actorOf(Props[ForkJoinActorBenchmark.PingPong])
        val pong = system.actorOf(Props[ForkJoinActorBenchmark.PingPong])
        (ping, pong)
      }

    pingPongs.foreach { case (ping, pong) => ping.tell(Message, pong) }

    pingPongs.foreach {
      case (ping, pong) =>
        val p = TestProbe()
        p.watch(ping)
        p.expectTerminated(ping, timeout)
        p.watch(pong)
        p.expectTerminated(pong, timeout)
    }
  }

  @Benchmark
  @Measurement(timeUnit = TimeUnit.MILLISECONDS)
  @OperationsPerInvocation(totalMessagesSameAsCores)
  def pingPongSameNumberOfActorsAsCores(): Unit = {
    val pingPongs =
      for {
        i <- 1 to (cores / 2)
      } yield {
        val ping = system.actorOf(Props[ForkJoinActorBenchmark.PingPong])
        val pong = system.actorOf(Props[ForkJoinActorBenchmark.PingPong])
        (ping, pong)
      }

    pingPongs.foreach { case (ping, pong) => ping.tell(Message, pong) }

    pingPongs.foreach {
      case (ping, pong) =>
        val p = TestProbe()
        p.watch(ping)
        p.expectTerminated(ping, timeout)
        p.watch(pong)
        p.expectTerminated(pong, timeout)
    }
  }

  @Benchmark
  @Measurement(timeUnit = TimeUnit.MILLISECONDS)
  @OperationsPerInvocation(totalMessagesMoreThanCores)
  def pingPongMoreActorsThanCores(): Unit = {
    val pingPongs =
      for {
        i <- 1 to moreThanCoresActorPairs
      } yield {
        val ping = system.actorOf(Props[ForkJoinActorBenchmark.PingPong])
        val pong = system.actorOf(Props[ForkJoinActorBenchmark.PingPong])
        (ping, pong)
      }

    pingPongs.foreach { case (ping, pong) => ping.tell(Message, pong) }

    pingPongs.foreach {
      case (ping, pong) =>
        val p = TestProbe()
        p.watch(ping)
        p.expectTerminated(ping, timeout)
        p.watch(pong)
        p.expectTerminated(pong, timeout)
    }
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
        beginning ! Message
        send(left - 1)
      }

    send(messages / 4) // we have 4 actors in the pipeline

    beginning ! stop

    p.expectTerminated(end, timeout)
  }
}

object ForkJoinActorBenchmark {
  final val stop = "stop"
  case object Message
  final val timeout = 15.seconds
  final val messages = 400000

  // update according to cpu
  final val cores = 8
  // 2 actors per
  final val moreThanCoresActorPairs = cores * 2
  final val lessThanCoresActorPairs = (cores / 2) - 1
  final val totalMessagesMoreThanCores = moreThanCoresActorPairs * messages
  final val totalMessagesLessThanCores = lessThanCoresActorPairs * messages
  final val totalMessagesSameAsCores = cores * messages

  class Pipe(next: Option[ActorRef]) extends Actor {
    def receive = {
      case m @ Message =>
        if (next.isDefined) next.get forward m
      case s @ `stop` =>
        context stop self
        if (next.isDefined) next.get forward s
    }
  }
  class PingPong extends Actor {
    var left = messages / 2
    def receive = {
      case Message =>

        if (left <= 1)
          context stop self

        sender() ! Message
        left -= 1
    }
  }
}
