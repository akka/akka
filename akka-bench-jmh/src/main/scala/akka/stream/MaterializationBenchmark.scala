/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream

import java.util.concurrent.TimeUnit
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl._
import org.openjdk.jmh.annotations._
import scala.concurrent.Await
import scala.concurrent.duration._

object MaterializationBenchmark {

  val flowWithMapBuilder = (numOfCombinators: Int) => {
    var source = Source.single(())
    for (_ <- 1 to numOfCombinators) {
      source = source.map(identity)
    }
    source.to(Sink.ignore)
  }

  val graphWithJunctionsBuilder = (numOfJunctions: Int) =>
    RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val broadcast = b.add(Broadcast[Unit](numOfJunctions))
      var outlet = broadcast.out(0)
      for (i <- 1 until numOfJunctions) {
        val merge = b.add(Merge[Unit](2))
        outlet ~> merge
        broadcast.out(i) ~> merge
        outlet = merge.out
      }

      Source.single(()) ~> broadcast
      outlet ~> Sink.ignore
      ClosedShape
    })

  val graphWithNestedImportsBuilder = (numOfNestedGraphs: Int) => {
    var flow: Graph[FlowShape[Unit, Unit], NotUsed] = Flow[Unit].map(identity)
    for (_ <- 1 to numOfNestedGraphs) {
      flow = GraphDSL.create(flow) { b ⇒ flow ⇒
        FlowShape(flow.in, flow.out)
      }
    }

    RunnableGraph.fromGraph(GraphDSL.create(flow) { implicit b ⇒ flow ⇒
      import GraphDSL.Implicits._
      Source.single(()) ~> flow ~> Sink.ignore
      ClosedShape
    })
  }

  val graphWithImportedFlowBuilder = (numOfFlows: Int) =>
    RunnableGraph.fromGraph(GraphDSL.create(Source.single(())) { implicit b ⇒ source ⇒
      import GraphDSL.Implicits._
      val flow = Flow[Unit].map(identity)
      var out: Outlet[Unit] = source.out
      for (i <- 0 until numOfFlows) {
        val flowShape = b.add(flow)
        out ~> flowShape
        out = flowShape.outlet
      }
      out ~> Sink.ignore
      ClosedShape
    })
}

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class MaterializationBenchmark {
  import MaterializationBenchmark._

  implicit val system = ActorSystem("MaterializationBenchmark")
  implicit val materializer = ActorMaterializer()

  var flowWithMap: RunnableGraph[NotUsed] = _
  var graphWithJunctions: RunnableGraph[NotUsed] = _
  var graphWithNestedImports: RunnableGraph[NotUsed] = _
  var graphWithImportedFlow: RunnableGraph[NotUsed] = _

  @Param(Array("1", "10", "100", "1000"))
  var complexity = 0

  @Setup
  def setup(): Unit = {
    flowWithMap = flowWithMapBuilder(complexity)
    graphWithJunctions = graphWithJunctionsBuilder(complexity)
    graphWithNestedImports = graphWithNestedImportsBuilder(complexity)
    graphWithImportedFlow = graphWithImportedFlowBuilder(complexity)
  }

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }

  @Benchmark
  def flow_with_map(): Unit = flowWithMap.run()

  @Benchmark
  def graph_with_junctions(): Unit = graphWithJunctions.run()

  @Benchmark
  def graph_with_nested_imports(): Unit = graphWithNestedImports.run()

  @Benchmark
  def graph_with_imported_flow(): Unit = graphWithImportedFlow.run()
}
