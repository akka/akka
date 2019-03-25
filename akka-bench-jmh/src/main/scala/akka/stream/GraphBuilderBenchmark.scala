/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.stream.scaladsl.RunnableGraph
import org.openjdk.jmh.annotations._

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class GraphBuilderBenchmark {

  @Param(Array("1", "10", "100", "1000"))
  var complexity = 0

  @Benchmark
  def flow_with_map(): RunnableGraph[NotUsed] =
    MaterializationBenchmark.flowWithMapBuilder(complexity)

  @Benchmark
  def graph_with_junctions_gradual(): RunnableGraph[NotUsed] =
    MaterializationBenchmark.graphWithJunctionsGradualBuilder(complexity)

  @Benchmark
  def graph_with_junctions_immediate(): RunnableGraph[NotUsed] =
    MaterializationBenchmark.graphWithJunctionsImmediateBuilder(complexity)

  @Benchmark
  def graph_with_imported_flow(): RunnableGraph[NotUsed] =
    MaterializationBenchmark.graphWithImportedFlowBuilder(complexity)

}
