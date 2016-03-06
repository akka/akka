/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.scaladsl._
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations._
import scala.concurrent._
import scala.concurrent.duration._

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class FlatMapMergeBenchmark {
  implicit val system = ActorSystem("FlatMapMergeBenchmark")
  val materializerSettings = ActorMaterializerSettings(system).withDispatcher("akka.test.stream-dispatcher")
  implicit val materializer = ActorMaterializer(materializerSettings)

  val NumberOfElements = 100000

  @Param(Array("0", "1", "10"))
  var NumberOfStreams = 0

  var graph: RunnableGraph[Future[Done]] = _

  def createSource(count: Int): Graph[SourceShape[Int], NotUsed] = akka.stream.Fusing.aggressive(Source.repeat(1).take(count))

  @Setup
  def setup():Unit = {
    val source = NumberOfStreams match {
      // Base line: process NumberOfElements-many elements from a single source without using flatMapMerge
      case 0 => createSource(NumberOfElements)
      // Stream merging: process NumberOfElements-many elements from n sources, each producing (NumberOfElements/n)-many elements
      case n =>
        val subSource = createSource(NumberOfElements / n)
        Source.repeat(()).take(n).flatMapMerge(n, _ => subSource)
    }
    graph = Source.fromGraph(source).toMat(Sink.ignore)(Keep.right)
  }

  @TearDown
  def shutdown():Unit = {
    Await.result(system.terminate(), 5.seconds)
  }

  @Benchmark
  @OperationsPerInvocation(100000) // Note: needs to match NumberOfElements.
  def flat_map_merge_100k_elements():Unit = {
    Await.result(graph.run(), Duration.Inf)
  }
}
