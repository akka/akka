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
    FlowGraph.closed() { implicit b =>
      import FlowGraph.Implicits._

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
    }

  val graphWithNestedImportsBuilder = (numOfNestedGraphs: Int) => {
    var flow: Graph[FlowShape[Unit, Unit], Unit] = Flow[Unit].map(identity)
    for (_ <- 1 to numOfNestedGraphs) {
      flow = FlowGraph.partial(flow) { b ⇒
        flow ⇒
          FlowShape(flow.inlet, flow.outlet)
      }
    }

    FlowGraph.closed(flow) { implicit b ⇒
      flow ⇒
        import FlowGraph.Implicits._
        Source.single(()) ~> flow ~> Sink.ignore
    }
  }

  val graphWithImportedFlowBuilder = (numOfFlows: Int) => {
    val flow = Flow[Unit].map(identity)
    FlowGraph.closed() { b ⇒
      val source = b.add(Source.single(()))
      var outlet = source
      for (i <- 0 until numOfFlows) {
        val flowShape = b.add(flow)
        b.addEdge(outlet, flowShape.inlet)
        outlet = flowShape.outlet
      }

      val sink = b.add(Sink.ignore)
      b.addEdge(outlet, sink)
    }
  }
}

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class MaterializationBenchmark {
  import MaterializationBenchmark._

  implicit val system = ActorSystem("MaterializationBenchmark")
  implicit val mat = ActorMaterializer()

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
