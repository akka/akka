/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class GraphBuilderBenchmark {

  @Param(Array("1", "10", "100", "1000"))
  var complexity = 0

  @Benchmark
  def flow_with_map(blackhole: Blackhole): Unit = {
    blackhole.consume(MaterializationBenchmark.flowWithMapBuilder(complexity))
  }

  @Benchmark
  def graph_with_junctions(blackhole: Blackhole): Unit = {
    blackhole.consume(MaterializationBenchmark.graphWithJunctionsBuilder(complexity))
  }

  @Benchmark
  def graph_with_nested_imports(blackhole: Blackhole): Unit = {
    blackhole.consume(MaterializationBenchmark.graphWithNestedImportsBuilder(complexity))
  }

  @Benchmark
  def graph_with_imported_flow(blackhole: Blackhole): Unit = {
    blackhole.consume(MaterializationBenchmark.graphWithImportedFlowBuilder(complexity))
  }
}
