/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
@Measurement(timeUnit = TimeUnit.MILLISECONDS)
class ByteString_toArray_Benchmark {

  val b = Array.ofDim[Byte](1024 * 10244)
  val bb = ByteString(b)
  /*
    Benchmark                                  Mode  Cnt    Score    Error  Units
    2.12
    ByteString_toArray_Benchmark.to_array  thrpt    3  537,116 ± 525,663  ops/s
    2.13 before fix
    ByteString_toArray_Benchmark.to_array  thrpt    3  165,869 ± 243,524  ops/s
    2.13 with fix #28114
    ByteString_toArray_Benchmark.to_array  thrpt    3  525,521 ± 346,830  ops/s
   */

  @Benchmark
  @OperationsPerInvocation(1000)
  def to_array(blackhole: Blackhole) = {

    for (_ <- 0 to 1000)
      blackhole.consume(bb.toArray[Byte])

  }

}
