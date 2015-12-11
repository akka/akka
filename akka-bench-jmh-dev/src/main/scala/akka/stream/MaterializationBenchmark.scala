/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.stream

import java.util.concurrent.TimeUnit
import akka.actor.ActorSystem
import akka.stream.scaladsl._
import org.openjdk.jmh.annotations._

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
    var flow: Graph[FlowShape[Unit, Unit], Unit] = Flow[Unit].map(identity)
    for (_ <- 1 to numOfNestedGraphs) {
      flow = GraphDSL.create(flow) { b ⇒
        flow ⇒
          FlowShape(flow.inlet, flow.outlet)
      }
    }

    RunnableGraph.fromGraph(GraphDSL.create(flow) { implicit b ⇒
      flow ⇒
        import GraphDSL.Implicits._
        Source.single(()) ~> flow ~> Sink.ignore
      ClosedShape
    })
  }

  val graphWithImportedFlowBuilder = (numOfFlows: Int) =>
    RunnableGraph.fromGraph(GraphDSL.create(Source.single(())) { implicit b ⇒ source ⇒
      import GraphDSL.Implicits._
      val flow = Flow[Unit].map(identity)
      var outlet: Outlet[Unit] = source.outlet
      for (i <- 0 until numOfFlows) {
        val flowShape = b.add(flow)
        outlet ~> flowShape
        outlet = flowShape.outlet
      }
      outlet ~> Sink.ignore
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

  var flowWithMap: RunnableGraph[Unit] = _
  var graphWithJunctions: RunnableGraph[Unit] = _
  var graphWithNestedImports: RunnableGraph[Unit] = _
  var graphWithImportedFlow: RunnableGraph[Unit] = _

  @Param(Array("1", "10", "100", "1000"))
  val complexity = 0

  @Setup
  def setup() {
    flowWithMap = flowWithMapBuilder(complexity)
    graphWithJunctions = graphWithJunctionsBuilder(complexity)
    graphWithNestedImports = graphWithNestedImportsBuilder(complexity)
    graphWithImportedFlow = graphWithImportedFlowBuilder(complexity)
  }

  @TearDown
  def shutdown() {
    system.shutdown()
    system.awaitTermination()
  }

  @Benchmark
  def flow_with_map() {
    flowWithMap.run()
  }

  @Benchmark
  def graph_with_junctions() {
    graphWithJunctions.run()
  }

  @Benchmark
  def graph_with_nested_imports() {
    graphWithNestedImports.run()
  }

  @Benchmark
  def graph_with_imported_flow() {
    graphWithImportedFlow.run()
  }
}
