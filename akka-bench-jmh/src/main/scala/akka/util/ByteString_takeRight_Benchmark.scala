/**
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import java.util.concurrent.TimeUnit

import akka.util.ByteString.{ ByteString1, ByteStrings }
import org.openjdk.jmh.annotations._

import scala.util.Random

@State(Scope.Benchmark)
@Measurement(timeUnit = TimeUnit.MILLISECONDS)
class ByteString_takeRight_Benchmark {

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
   --------------------------------- BASELINE ----------------------------------------------------------------------
   commit 0f2da7b26b5c4af35be87d2bd4a1a2392365df15
   [info] Benchmark                                                 Mode  Cnt          Score          Error  Units
   [info] ByteString_takeRight_Benchmark.bss_avg                   thrpt   40     486281.351 ±    14023.517  ops/s
   [info] ByteString_takeRight_Benchmark.bss_best                  thrpt   40     242096.480 ±     7029.451  ops/s
   [info] ByteString_takeRight_Benchmark.bss_greater_or_eq_to_len  thrpt   40  918217840.623 ± 12576483.168  ops/s
   [info] ByteString_takeRight_Benchmark.bss_negative              thrpt   40  895754845.562 ± 18965575.377  ops/s
   [info] ByteString_takeRight_Benchmark.bss_worst                 thrpt   40   11491250.938 ±   187949.479  ops/s

   --------------------------------- AFTER -------------------------------------------------------------------------

   ------ TODAY –––––––
   [info] Benchmark                                                 Mode  Cnt           Score          Error  Units
   [info] ByteString_takeRight_Benchmark.bss_avg                   thrpt   40      477028.892 ±     8846.994  ops/s
   [info] ByteString_takeRight_Benchmark.bss_best                  thrpt   40   106455228.296 ±   940597.222  ops/s
   [info] ByteString_takeRight_Benchmark.bss_greater_or_eq_to_len  thrpt   40   878842311.758 ±  9382511.848  ops/s
   [info] ByteString_takeRight_Benchmark.bss_negative              thrpt   40  1151544955.244 ± 21398125.610  ops/s
   [info] ByteString_takeRight_Benchmark.bss_worst                 thrpt   40      230406.055 ±     6075.782  ops/s

   */

  @Benchmark
  def bss_negative(): ByteString =
    bss.takeRight(n_neg)

  @Benchmark
  def bss_greater_or_eq_to_len(): ByteString =
    bss.takeRight(n_greater_or_eq_to_len)

  @Benchmark
  def bss_avg(): ByteString =
    bss.takeRight(n_avg)

  @Benchmark
  def bss_best(): ByteString =
    bss.takeRight(n_best)

  @Benchmark
  def bss_worst(): ByteString =
    bss.takeRight(n_worst)
}
