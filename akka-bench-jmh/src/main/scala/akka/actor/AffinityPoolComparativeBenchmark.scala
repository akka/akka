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
[info] # Run complete. Total time: 00:16:59
[info]
[info] Benchmark                                                      (dispatcher)  (throughPut)   Mode  Cnt        Score        Error  Units
[info] AffinityPoolComparativeBenchmark.pingPongAmongActors  default-fj-dispatcher             1  thrpt    5  2502334.107 ± 278377.287  ops/s
[info] AffinityPoolComparativeBenchmark.pingPongAmongActors  default-fj-dispatcher             2  thrpt    5  2805366.011 ± 135280.572  ops/s
[info] AffinityPoolComparativeBenchmark.pingPongAmongActors  default-fj-dispatcher             8  thrpt    5  2960842.633 ± 460100.314  ops/s
[info] AffinityPoolComparativeBenchmark.pingPongAmongActors  default-fj-dispatcher            16  thrpt    5  3041800.494 ± 133716.522  ops/s
[info] AffinityPoolComparativeBenchmark.pingPongAmongActors  default-fj-dispatcher           128  thrpt    5  3076335.176 ± 234049.240  ops/s
[info] AffinityPoolComparativeBenchmark.pingPongAmongActors  default-fj-dispatcher          1024  thrpt    5  3186194.318 ± 211764.149  ops/s
[info] AffinityPoolComparativeBenchmark.pingPongAmongActors    affinity-dispatcher             1  thrpt    5  2689985.150 ± 143917.601  ops/s
[info] AffinityPoolComparativeBenchmark.pingPongAmongActors    affinity-dispatcher             2  thrpt    5  3001036.136 ± 128558.229  ops/s
[info] AffinityPoolComparativeBenchmark.pingPongAmongActors    affinity-dispatcher             8  thrpt    5  3125854.869 ± 274591.343  ops/s
[info] AffinityPoolComparativeBenchmark.pingPongAmongActors    affinity-dispatcher            16  thrpt    5  3269744.502 ± 220918.228  ops/s
[info] AffinityPoolComparativeBenchmark.pingPongAmongActors    affinity-dispatcher           128  thrpt    5  3285935.273 ± 177037.309  ops/s
[info] AffinityPoolComparativeBenchmark.pingPongAmongActors    affinity-dispatcher          1024  thrpt    5  3324958.827 ± 600240.151  ops/s
[info] AffinityPoolComparativeBenchmark.pingPongAmongActors  fixed-size-dispatcher             1  thrpt    5  1323354.603 ± 106187.521  ops/s
[info] AffinityPoolComparativeBenchmark.pingPongAmongActors  fixed-size-dispatcher             2  thrpt    5  1726785.084 ±  79620.660  ops/s
[info] AffinityPoolComparativeBenchmark.pingPongAmongActors  fixed-size-dispatcher             8  thrpt    5  2528458.672 ±  49305.516  ops/s
[info] AffinityPoolComparativeBenchmark.pingPongAmongActors  fixed-size-dispatcher            16  thrpt    5  2840318.652 ± 149657.191  ops/s
[info] AffinityPoolComparativeBenchmark.pingPongAmongActors  fixed-size-dispatcher           128  thrpt    5  2995463.456 ± 413228.625  ops/s
[info] AffinityPoolComparativeBenchmark.pingPongAmongActors  fixed-size-dispatcher          1024  thrpt    5  3154314.716 ± 229499.876  ops/s
*/

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@Fork(1)
@Threads(1)
@Warmup(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS, batchSize = 1)
@Measurement(iterations = 20)
class AffinityPoolComparativeBenchmark {

  @Param(Array("1", "2", "8", "16", "128", "1024"))
  var throughPut = 0

  @Param(Array("default-fj-dispatcher", "affinity-dispatcher", "fixed-size-dispatcher"))
  var dispatcher = ""

  final val numActors = 256
  final val numMessagesPerActorPair = 40000
  final val totalNumberOfMessages = numMessagesPerActorPair * (numActors / 2)
  final val numThreads = 8

  implicit var system: ActorSystem = _

  var actorPairs: Vector[(ActorRef, ActorRef)] = null

  @Setup(Level.Trial)
  def setup(): Unit = {
    system = ActorSystem("AffinityPoolComparativeBenchmark", ConfigFactory.parseString(
      s"""| akka {
          |   log-dead-letters = off
          |   actor {
          |     default-fj-dispatcher {
          |       executor = "fork-join-executor"
          |       fork-join-executor {
          |         parallelism-min = $numThreads
          |         parallelism-factor = 1.0
          |         parallelism-max = $numThreads
          |       }
          |       throughput = $throughPut
          |     }
          |
          |     fixed-size-dispatcher {
          |       executor = "thread-pool-executor"
          |       thread-pool-executor {
          |         fixed-pool-size = $numThreads
          |     }
          |       throughput = $throughPut
          |     }
          |
          |     affinity-dispatcher {
          |       executor = "affinity-pool-executor"
          |       affinity-pool-executor {
          |         parallelism-min = $numThreads
          |         parallelism-factor = 1.0
          |         parallelism-max = $numThreads
          |         affinity-group-size = 10000
          |         cpu-affinity-strategies = [any]
          |     }
          |       throughput = $throughPut
          |     }
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
    actorPairs = startPingPongActorPairs(numMessagesPerActorPair, numActors / 2, dispatcher)
  }

  @TearDown(Level.Invocation)
  def tearDownActors(): Unit = {
    stopPingPongActorPairs(actorPairs)
  }

  @Benchmark
  @Measurement(timeUnit = TimeUnit.MILLISECONDS)
  @OperationsPerInvocation(totalNumberOfMessages)
  def pingPongAmongActors(): Unit = {
    initiatePingPongForPairs(actorPairs, inFlight = throughPut * 2)
    awaitTerminatedPingPongActorPairs(actorPairs)
  }

}
