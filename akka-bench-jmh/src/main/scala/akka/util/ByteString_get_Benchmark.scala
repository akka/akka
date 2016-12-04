/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.util

import java.util.concurrent.TimeUnit

import akka.util.ByteString.{ ByteString1, ByteStrings }
import org.openjdk.jmh.annotations.{ Benchmark, Measurement, Scope, State }

@State(Scope.Benchmark)
@Measurement(timeUnit = TimeUnit.MILLISECONDS)
class ByteString_get_Benchmark {
  val numStr = 4
  val numVec = 1024
  val size = numStr * numVec
  val str = List.fill[Byte](numStr)(0).mkString
  val bss = ByteStrings(Vector.fill(numVec)(ByteString1.fromString(str)))

  val n_best = 0
  val n_worst = size - 1
  val n_avg = size / 2 - 1

  /*
   --------------------------------- BASELINE -------------------------------------------------------------------
   commit 0f2da7b26b5c4af35be87d2bd4a1a2392365df15
   [info] Benchmark                                Mode  Cnt          Score         Error  Units
   [info] ByteString_get_Benchmark.bss_avg        thrpt   40     298246.372 ±    8249.457  ops/s
   [info] ByteString_get_Benchmark.bss_best       thrpt   40  127761495.109 ± 1791133.787  ops/s
   [info] ByteString_get_Benchmark.bss_iteration  thrpt   40         65.658 ±       1.106  ops/s
   [info] ByteString_get_Benchmark.bss_worst      thrpt   40     157434.473 ±    2688.289  ops/s

   --------------------------------- AFTER ----------------------------------------------------------------------

   ------ TODAY –––––––
   [info] Benchmark                                Mode  Cnt          Score        Error  Units
   [info] ByteString_get_Benchmark.bss_avg        thrpt   40    9752370.455 ±  14669.714  ops/s
   [info] ByteString_get_Benchmark.bss_best       thrpt   40  116327363.416 ± 213188.562  ops/s
   [info] ByteString_get_Benchmark.bss_iteration  thrpt   40       2539.810 ±     10.192  ops/s
   [info] ByteString_get_Benchmark.bss_worst      thrpt   40    8625257.769 ±  13954.126  ops/s
   */

  @Benchmark
  def bss_iteration(): Unit = {
    var i = 0
    while (i < size) {
      bss(i)
      i += 1
    }
  }

  @Benchmark
  def bss_best: Byte = bss(n_best)

  @Benchmark
  def bss_worst: Byte = bss(n_worst)

  @Benchmark
  def bss_avg: Byte = bss(n_avg)
}
