package akka.actor

import java.util.concurrent.TimeUnit

import akka.actor.BenchmarkActors._
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@Fork(1)
@Threads(1)
@Warmup(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS, batchSize = 1)
@Measurement(iterations = 10, time = 15, timeUnit = TimeUnit.SECONDS, batchSize = 1)
class CPUAffinityStrategiesBenchmark {

  @Param(Array("5", "25", "50"))
  var throughPut = 0

  @Param(Array("any", "same-core, any", "same-socket, any", "different-core, any", "different-socket, any"))
  var cpuAffinityStrategy = ""

  final val numThreads, numActors = 8
  final val numMessagesPerActorPair = 2000000
  final val totalNumberOfMessages = numMessagesPerActorPair * (numActors / 2)

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
  def shutdown(): Unit = tearDownSystem()

  @Benchmark
  @Measurement(timeUnit = TimeUnit.MILLISECONDS)
  @OperationsPerInvocation(totalNumberOfMessages)
  def pingPong(): Unit = benchmarkPingPongActors(numMessagesPerActorPair, numActors, "affinity-dispatcher", throughPut, timeout)

}
