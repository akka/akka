/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor

import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

import scala.concurrent.duration._
import java.util.concurrent.TimeUnit

import akka.actor.BenchmarkActors.{ Message, Stop }

import scala.concurrent.Await
import scala.annotation.tailrec

import BenchmarkActors._
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@Fork(1)
@Threads(1)
@Warmup(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS, batchSize = 1)
@Measurement(iterations = 20)
class ForkJoinActorBenchmark {
  import ForkJoinActorBenchmark._

  @Param(Array("fork-join-executor:any", "affinity-pool-executor:any", "affinity-pool-executor:same-core", "affinity-pool-executor:same-socket", "affinity-pool-executor:different-core", "affinity-pool-executor:different-socket"))
  var executorAndStrategy = ""

  //@Param(Array("1", "100"))
  @Param(Array("1"))
  var tpt = 0

  @Param(Array("1"))//, "2", "4", "20", "64"))
  var threads = ""

  implicit var system: ActorSystem = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    println(s"Number of cores: ${ForkJoinActorBenchmark.cores}")
    val Array(executor, cpuAffinityStrategy) = executorAndStrategy.split(':')
    system = ActorSystem("ForkJoinActorBenchmark", ConfigFactory.parseString(
      s"""| akka {
        |   log-dead-letters = off
        |   actor {
        |     default-dispatcher {
        |       executor = $executor
        |       fork-join-executor {
        |         parallelism-min = $threads
        |         parallelism-factor = 1
        |         parallelism-max = $threads
        |       }
        |       affinity-pool-executor {
        |         num-threads = $threads
        |         affinity-group-size = 10000
        |         cpu-affinity-strategies = [$cpuAffinityStrategy]
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
    pingPongActors = startPingPongActorPairs(messages, 1, "default-dispatcher")
    pingPongLessActorsThanCoresActors = startPingPongActorPairs(messages, lessThanCoresActorPairs, "default-dispatcher")
    pingPongSameNumberOfActorsAsCoresActors = startPingPongActorPairs(messages, cores / 2, "default-dispatcher")
    pingPongMoreActorsThanCoresActors = startPingPongActorPairs(messages, moreThanCoresActorPairs, "default-dispatcher")
  }

  @TearDown(Level.Invocation)
  def tearDownActors(): Unit = {
    stopPingPongActorPairs(pingPongActors, timeout)
    stopPingPongActorPairs(pingPongLessActorsThanCoresActors, timeout)
    stopPingPongActorPairs(pingPongSameNumberOfActorsAsCoresActors, timeout)
    stopPingPongActorPairs(pingPongMoreActorsThanCoresActors, timeout)
  }

  @Benchmark
  @Measurement(timeUnit = TimeUnit.MILLISECONDS)
  @OperationsPerInvocation(messages)
  def pingPong(): Unit = {
    // only one message in flight
    initiatePingPongForPairs(pingPongActors, inFlight = 1)
    awaitTerminatedPingPongActorPairs(pingPongActors, timeout)
  }

  @Benchmark
  @Measurement(timeUnit = TimeUnit.MILLISECONDS)
  @OperationsPerInvocation(messagesGuesstimate)
  def pingPongLessActorsThanCores(): Unit = {
    initiatePingPongForPairs(pingPongLessActorsThanCoresActors, inFlight = 2 * tpt)
    awaitTerminatedPingPongActorPairs(pingPongLessActorsThanCoresActors, timeout)
  }

  @Benchmark
  @Measurement(timeUnit = TimeUnit.MILLISECONDS)
  @OperationsPerInvocation(messagesGuesstimate)
  def pingPongSameNumberOfActorsAsCores(): Unit = {
    initiatePingPongForPairs(pingPongSameNumberOfActorsAsCoresActors, inFlight = 2 * tpt)
    awaitTerminatedPingPongActorPairs(pingPongSameNumberOfActorsAsCoresActors, timeout)
  }

  @Benchmark
  @Measurement(timeUnit = TimeUnit.MILLISECONDS)
  @OperationsPerInvocation(messagesGuesstimate)
  def pingPongMoreActorsThanCores(): Unit = {
    initiatePingPongForPairs(pingPongMoreActorsThanCoresActors, inFlight = 2 * tpt)
    awaitTerminatedPingPongActorPairs(pingPongMoreActorsThanCoresActors, timeout)
  }

  //  @Benchmark
  //  @Measurement(timeUnit = TimeUnit.MILLISECONDS)
  //  @OperationsPerInvocation(messages)
  def floodPipe(): Unit = {

    val end = system.actorOf(Props(classOf[BenchmarkActors.Pipe], None))
    val middle = system.actorOf(Props(classOf[BenchmarkActors.Pipe], Some(end)))
    val penultimate = system.actorOf(Props(classOf[BenchmarkActors.Pipe], Some(middle)))
    val beginning = system.actorOf(Props(classOf[BenchmarkActors.Pipe], Some(penultimate)))

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
  final val timeout = 15.seconds
  final val messages = 400000
  final val messagesGuesstimate = messages * 8

  // update according to cpu
  final val cores = Runtime.getRuntime.availableProcessors
  // 2 actors per
  final val moreThanCoresActorPairs = cores * 2
  final val lessThanCoresActorPairs = (cores / 2) - 1
  final val totalMessagesMoreThanCores = moreThanCoresActorPairs * messages
  final val totalMessagesLessThanCores = lessThanCoresActorPairs * messages
  final val totalMessagesSameAsCores = cores * messages

}
