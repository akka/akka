/*
 * Copyright (C) 2018-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import akka.actor.ActorSystem
import akka.stream.scaladsl._
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.duration._

object StatefulMapConcatBenchmark {
  final val OperationsPerInvocation = 100000

}

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class StatefulMapConcatBenchmark {

  import StatefulMapConcatBenchmark._

  private val config = ConfigFactory.parseString("""
   akka.actor.default-dispatcher {
     executor = "fork-join-executor"
     fork-join-executor {
       parallelism-factor = 1
     }
   }
   """)

  private implicit val system: ActorSystem = ActorSystem("StatefulMapConcatBenchmark", config)

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }

  private val emptyArray = new Array[Int](0)
  private val statefulMapConcatEmptyArray =
    Source.repeat(0).take(OperationsPerInvocation).mapConcat(_ => emptyArray).toMat(Sink.ignore)(Keep.right)

  @Benchmark
  @OperationsPerInvocation(OperationsPerInvocation)
  def benchConcatEmptyArray(): Unit =
    Await.result(statefulMapConcatEmptyArray.run(), Duration.Inf)

}
