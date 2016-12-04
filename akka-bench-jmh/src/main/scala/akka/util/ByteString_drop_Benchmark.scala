/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.util

import java.util.concurrent.TimeUnit

import akka.util.ByteString.{ ByteString1, ByteStrings }
import org.openjdk.jmh.annotations._

import scala.util.Random

@State(Scope.Benchmark)
@Measurement(timeUnit = TimeUnit.MILLISECONDS)
class ByteString_drop_Benchmark {

  val str = List.fill[Byte](4)(0).mkString
  val numVec = 1024
  val bss = ByteStrings(Vector.fill(numVec)(ByteString1.fromString(str)))

  val rand = new Random()
  val len = str.size * numVec
  val n_greater_or_eq_to_len = len + rand.nextInt(Int.MaxValue - len)
  val n_neg = rand.nextInt(Int.MaxValue) * -1
  val n_avg = len / 2
  val n_best = 1
  val n_worst = len - 1

  /*
   --------------------------------- BASELINE ------------------------------------------------------------------
   commit 0f2da7b26b5c4af35be87d2bd4a1a2392365df15
   [info] Benchmark                                            Mode  Cnt          Score         Error  Units
   [info] ByteString_drop_Benchmark.bss_avg                   thrpt   40     560979.826 ±    3124.876  ops/s
   [info] ByteString_drop_Benchmark.bss_best                  thrpt   40   11367339.399 ±   98228.290  ops/s
   [info] ByteString_drop_Benchmark.bss_greater_or_eq_to_len  thrpt   40  301077170.894 ± 6879088.905  ops/s
   [info] ByteString_drop_Benchmark.bss_iteration             thrpt   40        106.565 ±       0.572  ops/s
   [info] ByteString_drop_Benchmark.bss_negative              thrpt   40  353064310.406 ± 4106542.258  ops/s
   [info] ByteString_drop_Benchmark.bss_worst                 thrpt   40     253302.470 ±    2162.730  ops/s

   --------------------------------- AFTER ----------------------------------------------------------------------

   ------ TODAY –––––––
   [info] Benchmark                                            Mode  Cnt          Score         Error  Units
   [info] ByteString_drop_Benchmark.bss_avg                   thrpt   40    5665514.987 ±  129576.190  ops/s
   [info] ByteString_drop_Benchmark.bss_best                  thrpt   40    5903613.985 ±   81600.595  ops/s
   [info] ByteString_drop_Benchmark.bss_greater_or_eq_to_len  thrpt   40  267368817.381 ± 2870511.427  ops/s
   [info] ByteString_drop_Benchmark.bss_iteration             thrpt   40       1113.068 ±      12.832  ops/s
   [info] ByteString_drop_Benchmark.bss_negative              thrpt   40  290322333.659 ± 5514237.109  ops/s
   [info] ByteString_drop_Benchmark.bss_worst                 thrpt   40    7605597.466 ±   98944.995  ops/s
   */

  @Benchmark
  def bss_negative(): ByteString =
    bss.drop(n_neg)

  @Benchmark
  def bss_greater_or_eq_to_len(): ByteString =
    bss.drop(n_greater_or_eq_to_len)

  @Benchmark
  def bss_avg(): ByteString =
    bss.drop(n_avg)

  @Benchmark
  def bss_best(): ByteString =
    bss.drop(n_best)

  @Benchmark
  def bss_worst(): ByteString =
    bss.drop(n_worst)

  @Benchmark
  def bss_iteration(): Unit = {
    var i = 0
    while (i < len) {
      bss.drop(i)
      i += 1
    }
  }
}
