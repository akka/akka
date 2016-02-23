/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations._

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class GraphBuilderBenchmark {

  @Param(Array("1", "10", "100", "1000"))
  val complexity = 0

  @Benchmark
  def flow_with_map() {
    MaterializationBenchmark.flowWithMapBuilder(complexity)
  }

  @Benchmark
  def graph_with_junctions() {
    MaterializationBenchmark.graphWithJunctionsBuilder(complexity)
  }

  @Benchmark
  def graph_with_nested_imports() {
    MaterializationBenchmark.graphWithNestedImportsBuilder(complexity)
  }

  @Benchmark
  def graph_with_imported_flow() {
    MaterializationBenchmark.graphWithImportedFlowBuilder(complexity)
  }
}
