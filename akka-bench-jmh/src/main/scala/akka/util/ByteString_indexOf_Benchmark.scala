/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.util

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._

@State(Scope.Benchmark)
@Measurement(timeUnit = TimeUnit.MILLISECONDS)
class ByteString_indexOf_Benchmark {
  val start = ByteString("abcdefg") ++ ByteString("hijklmno") ++ ByteString("pqrstuv")
  val bss = start ++ start ++ start ++ start ++ start ++ ByteString("xyzåäö")

  val bs = bss.compact // compacted

  /*
    original
    Benchmark                                                      Mode  Cnt       Score         Error  Units
    ByteString_indexOf_Benchmark.bs1_indexOf_from                 thrpt   20   981695.071 ±  15056.187  ops/s
    ByteString_indexOf_Benchmark.bss_indexOf_from_best_case       thrpt   20   276629.070 ±   3454.661  ops/s
    ByteString_indexOf_Benchmark.bss_indexOf_from_far_index_case  thrpt   20  2757883.683 ±  32969.502  ops/s
    ByteString_indexOf_Benchmark.bss_indexOf_from_worst_case      thrpt   20   279411.806 ±   6543.690  ops/s

    optimized
    ByteString_indexOf_Benchmark.bs1_indexOf_from                 thrpt   20   1053877.663 ±  50615.182  ops/s
    ByteString_indexOf_Benchmark.bss_indexOf_from_best_case       thrpt   20  19372459.950 ± 372521.940  ops/s
    ByteString_indexOf_Benchmark.bss_indexOf_from_far_index_case  thrpt   20  14709306.673 ± 179308.443  ops/s
    ByteString_indexOf_Benchmark.bss_indexOf_from_worst_case      thrpt   20  21669849.879 ± 183427.427  ops/s
   */

  @Benchmark
  def bss_indexOf_from_worst_case: Int = bss.indexOf("ö", 1)

  @Benchmark
  def bss_indexOf_from_far_index_case: Int = bss.indexOf("ö", 112)

  @Benchmark
  def bss_indexOf_from_best_case: Int = bss.indexOf("a", 0)

  @Benchmark
  def bs1_indexOf_from: Int = bs.indexOf("ö", 5)

}
