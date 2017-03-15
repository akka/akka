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
import scala.annotation.tailrec

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@Fork(1)
@Threads(1)
@Warmup(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS, batchSize = 1)
@Measurement(iterations = 20)
class ForkJoinActorBenchmark {
  import ForkJoinActorBenchmark._

  @Param(Array("5"))
  var tpt = 0

  @Param(Array("1"))
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

  var pingPongActors: Vector[(ActorRef, ActorRef)] = null
  var pingPongLessActorsThanCoresActors: Vector[(ActorRef, ActorRef)] = null
  var pingPongSameNumberOfActorsAsCoresActors: Vector[(ActorRef, ActorRef)] = null
  var pingPongMoreActorsThanCoresActors: Vector[(ActorRef, ActorRef)] = null

  @Setup(Level.Invocation)
  def setupActors(): Unit = {
    pingPongActors = startActors(1)
    pingPongLessActorsThanCoresActors = startActors(lessThanCoresActorPairs)
    pingPongSameNumberOfActorsAsCoresActors = startActors(cores / 2)
    pingPongMoreActorsThanCoresActors = startActors(moreThanCoresActorPairs)
  }

  @TearDown(Level.Invocation)
  def tearDownActors(): Unit = {
    stopActors(pingPongActors)
    stopActors(pingPongLessActorsThanCoresActors)
    stopActors(pingPongSameNumberOfActorsAsCoresActors)
    stopActors(pingPongMoreActorsThanCoresActors)
  }

  def startActors(n: Int): Vector[(ActorRef, ActorRef)] = {
    for {
      i <- (1 to n).toVector
    } yield {
      val ping = system.actorOf(Props[ForkJoinActorBenchmark.PingPong])
      val pong = system.actorOf(Props[ForkJoinActorBenchmark.PingPong])
      (ping, pong)
    }
  }

  def stopActors(refs: Vector[(ActorRef, ActorRef)]): Unit = {
    if (refs ne null) {
      refs.foreach {
        case (ping, pong) =>
          system.stop(ping)
          system.stop(pong)
      }
      awaitTerminated(refs)
    }
  }

  def awaitTerminated(refs: Vector[(ActorRef, ActorRef)]): Unit = {
    if (refs ne null) refs.foreach {
      case (ping, pong) =>
        val p = TestProbe()
        p.watch(ping)
        p.expectTerminated(ping, timeout)
        p.watch(pong)
        p.expectTerminated(pong, timeout)
    }
  }

  def sendMessage(refs: Vector[(ActorRef, ActorRef)], inFlight: Int): Unit = {
    for {
      (ping, pong) <- refs
      _ <- 1 to inFlight
    } {
      ping.tell(Message, pong)
    }
  }

  @Benchmark
  @Measurement(timeUnit = TimeUnit.MILLISECONDS)
  @OperationsPerInvocation(messages)
  def pingPong(): Unit = {
    // only one message in flight
    sendMessage(pingPongActors, inFlight = 1)
    awaitTerminated(pingPongActors)
  }

  @Benchmark
  @Measurement(timeUnit = TimeUnit.MILLISECONDS)
  @OperationsPerInvocation(totalMessagesLessThanCores)
  def pingPongLessActorsThanCores(): Unit = {
    sendMessage(pingPongLessActorsThanCoresActors, inFlight = 2 * tpt)
    awaitTerminated(pingPongLessActorsThanCoresActors)
  }

  @Benchmark
  @Measurement(timeUnit = TimeUnit.MILLISECONDS)
  @OperationsPerInvocation(totalMessagesSameAsCores)
  def pingPongSameNumberOfActorsAsCores(): Unit = {
    sendMessage(pingPongSameNumberOfActorsAsCoresActors, inFlight = 2 * tpt)
    awaitTerminated(pingPongSameNumberOfActorsAsCoresActors)
  }

  @Benchmark
  @Measurement(timeUnit = TimeUnit.MILLISECONDS)
  @OperationsPerInvocation(totalMessagesMoreThanCores)
  def pingPongMoreActorsThanCores(): Unit = {
    sendMessage(pingPongMoreActorsThanCoresActors, inFlight = 2 * tpt)
    awaitTerminated(pingPongMoreActorsThanCoresActors)
  }

  //  @Benchmark
  //  @Measurement(timeUnit = TimeUnit.MILLISECONDS)
  //  @OperationsPerInvocation(messages)
  def floodPipe(): Unit = {

    val end = system.actorOf(Props(classOf[ForkJoinActorBenchmark.Pipe], None))
    val middle = system.actorOf(Props(classOf[ForkJoinActorBenchmark.Pipe], Some(end)))
    val penultimate = system.actorOf(Props(classOf[ForkJoinActorBenchmark.Pipe], Some(middle)))
    val beginning = system.actorOf(Props(classOf[ForkJoinActorBenchmark.Pipe], Some(penultimate)))

    val p = TestProbe()
    p.watch(end)

    @tailrec def send(left: Int): Unit =
      if (left > 0) {
        beginning ! Message
        send(left - 1)
      }

    send(messages / 4) // we have 4 actors in the pipeline

    beginning ! Stop

    p.expectTerminated(end, timeout)
  }
}

object ForkJoinActorBenchmark {
  case object Stop
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
      case Message =>
        if (next.isDefined) next.get forward Message
      case Stop =>
        context stop self
        if (next.isDefined) next.get forward Stop
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
