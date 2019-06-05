/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.scaladsl._
import org.openjdk.jmh.annotations._

import scala.concurrent._
import scala.concurrent.duration._

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class EmptySourceBenchmark {
  implicit val system = ActorSystem("EmptySourceBenchmark")
  val materializerSettings = ActorMaterializerSettings(system).withDispatcher("akka.test.stream-dispatcher")
  implicit val materializer = ActorMaterializer(materializerSettings)

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }

  val setup = Source.empty[String].toMat(Sink.ignore)(Keep.right)

  @Benchmark def empty(): Unit =
    Await.result(setup.run(), Duration.Inf)

  /*
    (not serious benchmark, just sanity check: run on macbook 15, late 2013)

    While it was a PublisherSource:
     [info] EmptySourceBenchmark.empty  thrpt   10  11.219 ± 6.498  ops/ms

    Rewrite to GraphStage:
     [info] EmptySourceBenchmark.empty  thrpt   10  17.556 ± 2.865  ops/ms

 */
}
