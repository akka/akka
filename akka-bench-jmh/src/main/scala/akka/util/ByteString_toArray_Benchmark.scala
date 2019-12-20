/*
 * Copyright (C) 2014-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

@State(Scope.Benchmark)
@Measurement(timeUnit = TimeUnit.MILLISECONDS)
class ByteString_toArray_Benchmark {

  var bs: ByteString = _

  @Param(Array("10", "100", "1000"))
  var kb = 0

  /*
    akka-bench-jmh/jmh:run -f 1 -wi 3 -i 3 .*ByteString_toArray_Benchmark.*
    Benchmark             (kb)  Mode   Cnt    Score     Error  Units
    2.12
    composed_bs_to_array    10  thrpt    3    4,658 ±  14,923  ops/s
    composed_bs_to_array   100  thrpt    3    5,341 ±   0,384  ops/s
    composed_bs_to_array  1000  thrpt    3    5,362 ±   1,020  ops/s
    single_bs_to_array      10  thrpt    3  527,457 ± 279,643  ops/s
    single_bs_to_array     100  thrpt    3  520,702 ±  50,317  ops/s
    single_bs_to_array    1000  thrpt    3  523,216 ± 108,398  ops/s
    2.13 before (second) fix
    Benchmark             (kb)   Mode  Cnt    Score     Error  Units
    composed_bs_to_array    10  thrpt    3    0,099 ±   0,012  ops/s
    composed_bs_to_array   100  thrpt    3    0,101 ±   0,074  ops/s
    composed_bs_to_array  1000  thrpt    3    0,101 ±   0,059  ops/s
    single_bs_to_array      10  thrpt    3  515,178 ± 461,701  ops/s
    single_bs_to_array     100  thrpt    3  515,013 ± 162,183  ops/s
    single_bs_to_array    1000  thrpt    3  508,825 ± 182,691  ops/s
    2.13 with (second) fix
    composed_bs_to_array    10  thrpt    3    5,586 ±    0,992  ops/s
    composed_bs_to_array   100  thrpt    3    5,335 ±    1,976  ops/s
    composed_bs_to_array  1000  thrpt    3    5,434 ±    0,917  ops/s
    single_bs_to_array      10  thrpt    3  500,953 ± 1411,704  ops/s
    single_bs_to_array     100  thrpt    3  531,516 ±   99,146  ops/s
    single_bs_to_array    1000  thrpt    3  530,254 ±  177,647  ops/s
   */

  @Setup
  def setup(): Unit = {
    val bytes = Array.ofDim[Byte](1024 * 10244)
    bs = ByteString(bytes)
  }

  @Benchmark
  @OperationsPerInvocation(100)
  def single_bs_to_array(blackhole: Blackhole): Unit = {

    for (_ <- 0 to 100)
      blackhole.consume(bs.toArray[Byte])

  }

  @Benchmark
  def composed_bs_to_array(blackhole: Blackhole): Unit = {
    var b = ByteString.empty
    for (_ <- 0 to 100) {
      b = b ++ bs
    }
    blackhole.consume(b.toArray[Byte])
  }

}
