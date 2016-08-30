/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.util

import java.util.concurrent.TimeUnit

import akka.util.ByteString.{ ByteString1C, ByteStrings }
import org.openjdk.jmh.annotations._

@State(Scope.Benchmark)
@Measurement(timeUnit = TimeUnit.MILLISECONDS)
class ByteStrings_apply_Benchmark {

  val bs_mini = ByteString(Array.ofDim[Byte](8))

  val bss_small = ByteStrings(
    Vector.fill(10)(bs_mini.asInstanceOf[ByteString1C].toByteString1), 10 * bs_mini.length
  ).asInstanceOf[ByteStrings].fastScannable

  val bss_large = ByteStrings(
    Vector.fill(100)(bs_mini.asInstanceOf[ByteString1C].toByteString1), 100 * bs_mini.length
  ).asInstanceOf[ByteStrings].fastScannable

  val bss_xlarge = ByteStrings(
    Vector.fill(10000)(bs_mini.asInstanceOf[ByteString1C].toByteString1), 1000 * bs_mini.length
  ).asInstanceOf[ByteStrings].fastScannable

  /*
    --------------------------------- BEFORE ---------------------------------------------------------
    [info] Benchmark                                        Mode  Cnt       Score       Error  Units
    [info] ByteStrings_apply_Benchmark.bss_small_looping   thrpt  200  598181.458 ± 32063.505  ops/s
    [info] ByteStrings_apply_Benchmark.bss_large_looping   thrpt  200    3590.394 ±   114.742  ops/s
    [info] ByteStrings_apply_Benchmark.bss_xlarge_looping  thrpt  200      29.046 ±     1.199  ops/s


    --------------------------------- AFTER ---------------------------------------------------------
    [info] Benchmark                                        Mode  Cnt       Score       Error  Units
    [info] ByteStrings_apply_Benchmark.bss_small_looping   thrpt  200  964975.141 ± 53184.968  ops/s
    [info] ByteStrings_apply_Benchmark.bss_large_looping   thrpt  200   70032.915 ±  2492.263  ops/s
    [info] ByteStrings_apply_Benchmark.bss_xlarge_looping  thrpt  200    7552.745 ±    87.114  ops/s

   */

  @Benchmark
  def bss_small_looping(): Unit = {
    var i = 0
    while (i < bss_small.length) {
      bss_small(i)
      i += 1
    }
  }

  @Benchmark
  def bss_large_looping(): Unit = {
    var i = 0
    while (i < bss_large.length) {
      bss_large(i)
      i += 1
    }
  }

  @Benchmark
  def bss_xlarge_looping(): Unit = {
    var i = 0
    while (i < bss_xlarge.length) {
      bss_xlarge(i)
      i += 1
    }
  }

}
