/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor

import java.util.concurrent.TimeUnit

import akka.actor.BenchmarkActors._
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

import scala.concurrent.Await

/*
[info] # Run complete. Total time: 00:11:35
[info]
[info] Benchmark                                                 (throughput)  (waitingStrat)  Mode  Cnt     Score     Error  Units
[info] AffinityPoolWaitingStrategyBenchmark.pingPongAmongActors             1           sleep  avgt   20  4227.847 ± 454.128  ns/op
[info] AffinityPoolWaitingStrategyBenchmark.pingPongAmongActors             1           yield  avgt   20  3018.116 ± 314.430  ns/op
[info] AffinityPoolWaitingStrategyBenchmark.pingPongAmongActors             1       busy-spin  avgt   20   383.004 ±  15.540  ns/op
[info] AffinityPoolWaitingStrategyBenchmark.pingPongAmongActors             5           sleep  avgt   20   867.091 ±  38.891  ns/op
[info] AffinityPoolWaitingStrategyBenchmark.pingPongAmongActors             5           yield  avgt   20   372.663 ±   8.739  ns/op
[info] AffinityPoolWaitingStrategyBenchmark.pingPongAmongActors             5       busy-spin  avgt   20   324.673 ±   5.964  ns/op
[info] AffinityPoolWaitingStrategyBenchmark.pingPongAmongActors            64           sleep  avgt   20   266.367 ±   5.290  ns/op
[info] AffinityPoolWaitingStrategyBenchmark.pingPongAmongActors            64           yield  avgt   20   319.189 ±  89.917  ns/op
[info] AffinityPoolWaitingStrategyBenchmark.pingPongAmongActors            64       busy-spin  avgt   20   302.987 ±  10.299  ns/op
*/

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@Fork(1)
@Threads(1)
@Warmup(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS, batchSize = 1)
@Measurement(iterations = 20)
class AffinityPoolWaitingStrategyBenchmark {

  final val numActors = 2
  final val numMessagesPerActorPair = 40000
  final val totalNumberOfMessages = numMessagesPerActorPair * (numActors / 2)
  final val numThreads = 8

  implicit var system: ActorSystem = _
  var actorPairs: Vector[(ActorRef, ActorRef)] = null

  @Param(Array("sleep", "yield", "busy-spin"))
  var waitingStrat = ""

  @Param(Array("1", "5", "64"))
  var throughput = 0

  @Setup(Level.Trial)
  def setup(): Unit = {
    system = ActorSystem("AffinityPoolWaitingStrategyBenchmark", ConfigFactory.parseString(
      s""" | akka {
         |   log-dead-letters = off
         |   actor {
         |     affinity-dispatcher {
         |       executor = "affinity-pool-executor"
         |       affinity-pool-executor {
         |         parallelism-min = $numThreads
         |         parallelism-factor = 1.0
         |         parallelism-max = $numThreads
         |         affinity-group-size = 10000
         |         worker-waiting-strategy = $waitingStrat
         |     }
         |     throughput = $throughput
         |    }
         |
         |   }
         | }
      """.stripMargin
    ))
  }

  @TearDown(Level.Trial)
  def shutdown(): Unit = {
    system.terminate()
    Await.ready(system.whenTerminated, timeout)
  }

  @Setup(Level.Invocation)
  def setupActors(): Unit = {
    actorPairs = startPingPongActorPairs(numMessagesPerActorPair, numActors / 2, "affinity-dispatcher")
  }

  @TearDown(Level.Invocation)
  def tearDownActors(): Unit = {
    stopPingPongActorPairs(actorPairs)
  }

  @Benchmark
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  @OperationsPerInvocation(totalNumberOfMessages)
  def pingPongAmongActors(): Unit = {
    initiatePingPongForPairs(actorPairs, inFlight = throughput * 2)
    awaitTerminatedPingPongActorPairs(actorPairs)
  }

}
