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
import java.util.concurrent.CountDownLatch
import akka.actor.SupervisorSpec.PingPongActor

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@Fork(1)
@Threads(1)
@Warmup(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS, batchSize = 1)
@Measurement(iterations = 10, time = 15, timeUnit = TimeUnit.SECONDS, batchSize = 1)
class ForkJoinActorBenchmark {
  import ForkJoinActorBenchmark._

  @Param(Array("5", "25", "50"))
  var tpt = 0

  @Param(Array(coresStr)) // coresStr, cores2xStr, cores4xStr
  var threads = ""

  @Param(Array("SingleConsumerOnlyUnboundedMailbox")) //"default"
  var mailbox = ""

  implicit var system: ActorSystem = _

  @Setup(Level.Trial)
  def setup(): Unit = {

    require(
      Runtime.getRuntime.availableProcessors == cores,
      s"Update the cores constant to ${Runtime.getRuntime.availableProcessors}"
    )

    val mailboxConf = mailbox match {
      case "default" => ""
      case "SingleConsumerOnlyUnboundedMailbox" =>
        s"""default-mailbox.mailbox-type = "${classOf[akka.dispatch.SingleConsumerOnlyUnboundedMailbox].getName}""""
    }

    system = ActorSystem("ForkJoinActorBenchmark", ConfigFactory.parseString(
      s"""
        akka {
          log-dead-letters = off
           actor {
             default-dispatcher {
               executor = "fork-join-executor"
               fork-join-executor {
                 parallelism-min = $threads
                 parallelism-factor = 1
                 parallelism-max = $threads
               }
               throughput = $tpt
             }
             $mailboxConf
           }
         }
      """
    ))
  }

  @TearDown(Level.Trial)
  def shutdown(): Unit = {
    system.terminate()
    Await.ready(system.whenTerminated, 15.seconds)
  }

  def startActors(n: Int, props: Props): Vector[(ActorRef, ActorRef)] = {
    for {
      i <- (1 to n).toVector
    } yield {
      val ping = system.actorOf(props)
      val pong = system.actorOf(props)
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

  private def printProgress(totalMessages: Long, numActors: Int, startNanoTime: Long): Unit = {
    val durationMicros = (System.nanoTime() - startNanoTime) / 1000
    println(f"  $totalMessages messages by $numActors actors took ${durationMicros / 1000} ms, " +
      f"${totalMessages.toDouble / durationMicros}%,.2f M msg/s")
  }

  @Benchmark
  @OperationsPerInvocation(messages)
  def pingPong(): Unit = {
    val latch = new CountDownLatch(2)
    val actors = startActors(1, pingPongProps(latch))
    val startNanoTime = System.nanoTime()
    sendMessage(actors, inFlight = 2 * tpt)
    latch.await(timeout.toSeconds, TimeUnit.SECONDS)
    printProgress(messages, 2, startNanoTime)
  }

  @Benchmark
  @OperationsPerInvocation(totalMessagesLessThanCores)
  def pingPongLessActorsThanCores(): Unit = {
    val latch = new CountDownLatch(lessThanCoresActorPairs * 2)
    val actors = startActors(lessThanCoresActorPairs, pingPongProps(latch))
    val startNanoTime = System.nanoTime()
    sendMessage(actors, inFlight = 2 * tpt)
    latch.await(timeout.toSeconds, TimeUnit.SECONDS)
    printProgress(totalMessagesLessThanCores, actors.size * 2, startNanoTime)
  }

  @Benchmark
  @OperationsPerInvocation(totalMessagesSameAsCores)
  def pingPongSameNumberOfActorsAsCores(): Unit = {
    val latch = new CountDownLatch(cores)
    val actors = startActors(cores / 2, pingPongProps(latch))
    val startNanoTime = System.nanoTime()
    sendMessage(actors, inFlight = 2 * tpt)
    latch.await(timeout.toSeconds, TimeUnit.SECONDS)
    printProgress(totalMessagesSameAsCores, actors.size * 2, startNanoTime)
  }

  @Benchmark
  @OperationsPerInvocation(totalMessagesMoreThanCores)
  def pingPongMoreActorsThanCores(): Unit = {
    val latch = new CountDownLatch(moreThanCoresActorPairs * 2)
    val actors = startActors(moreThanCoresActorPairs, pingPongProps(latch))
    val startNanoTime = System.nanoTime()
    sendMessage(actors, inFlight = 2 * tpt)
    latch.await(timeout.toSeconds, TimeUnit.SECONDS)
    printProgress(totalMessagesMoreThanCores, actors.size * 2, startNanoTime)
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
  final val timeout = 30.seconds
  final val messages = 2000000 // messages per actor pair

  // Constants because they are used in annotations
  // update according to cpu
  final val cores = 24
  final val coresStr = "24"
  final val cores2xStr = "48"
  final val cores4xStr = "96"
  // 2 actors per pair
  final val moreThanCoresActorPairs = cores
  final val lessThanCoresActorPairs = cores / 4
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

  def pingPongProps(latch: CountDownLatch): Props =
    Props(new PingPong(latch))

  class PingPong(latch: CountDownLatch) extends Actor {
    var left = messages / 2
    def receive = {
      case Message =>

        if (left == 0) {
          latch.countDown()
          context stop self
        }

        sender() ! Message
        left -= 1
    }
  }
}
