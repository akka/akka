package akka.actor

import java.util.concurrent.TimeUnit

import akka.actor.BenchmarkActors._
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

import scala.concurrent.Await

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@Fork(1)
@Threads(1)
@Warmup(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS, batchSize = 1)
@Measurement(iterations = 20)
class CPUAffinityStrategiesBenchmark {

  @Param(Array("1", "128", "1024"))
  var throughPut = 0

  @Param(Array("any", "same-core, any", "same-socket, any", "different-core, any", "different-socket, any"))
  var cpuAffinityStrategy = ""

  final val numActors = 256
  final val numMessagesPerActorPair = 40000
  final val totalNumberOfMessages = numMessagesPerActorPair * (numActors / 2)
  final val numThreads = 8

  implicit var system: ActorSystem = _

  var actorPairs: Vector[(ActorRef, ActorRef)] = null

  @Setup(Level.Trial)
  def setup(): Unit = {
    system = ActorSystem("CPUAffinityStrategiesBenchmark", ConfigFactory.parseString(
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
         |         cpu-affinity-strategies = [$cpuAffinityStrategy]
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
    actorPairs = startPingPongActorPairs(numMessagesPerActorPair, numActors / 2, "affinity-dispatcher")
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
