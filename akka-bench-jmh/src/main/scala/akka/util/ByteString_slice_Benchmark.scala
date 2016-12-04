/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.util

import java.util.concurrent.TimeUnit

import akka.util.ByteString.{ ByteString1, ByteStrings }
import org.openjdk.jmh.annotations.{ Benchmark, Measurement, Scope, State }

import scala.util.Random

@State(Scope.Benchmark)
@Measurement(timeUnit = TimeUnit.MILLISECONDS)
class ByteString_slice_Benchmark {
  val numVec = 1024
  val str = List.fill[Byte](4)(0).mkString
  val bss = ByteStrings(Vector.fill(numVec)(ByteString1.fromString(str)))

  val rand = new Random()
  val len = str.size * numVec
  val n_greater_or_eq_to_len = len + rand.nextInt(Int.MaxValue - len)
  val n_neg = rand.nextInt(Int.MaxValue) * -1

  val from_half = 1
  val until_half = len / 2
  val from_half2 = len / 2 - 1
  val until_half2 = len - 1
  val from_half3 = len / 4
  val until_half3 = len * 3 / 4 - 1

  // take(1)
  val from_take1 = 0
  val until_take1 = 1
  // dropRight(1)
  val from_dropRight1 = 0
  val until_dropRight1 = len - 1
  // takeRight(1)
  val from_takeRight1 = len - 1
  val until_takeRight1 = len
  // drop(1)
  val from_drop1 = 1
  val until_drop1 = len

  // slice(n, n+1)
  val from_case = len / 2 - 1
  val until_case = len / 2
  val from_case2 = len / 2
  val until_case2 = len / 2 + 1
  val from_case3 = 1
  val until_case3 = 2
  val from_case4 = len - 2
  val until_case4 = len - 1

  /*
   --------------------------------- BASELINE -----------------------------------------------
   commit 0f2da7b26b5c4af35be87d2bd4a1a2392365df15
   [info] Benchmark                                             Mode  Cnt          Score         Error  Units
   [info] ByteString_slice_Benchmark.bss_case                  thrpt   40      25843.383 ±     666.859  ops/s
   [info] ByteString_slice_Benchmark.bss_case2                 thrpt   40      25119.135 ±     452.224  ops/s
   [info] ByteString_slice_Benchmark.bss_case3                 thrpt   40      13026.250 ±     125.890  ops/s
   [info] ByteString_slice_Benchmark.bss_case4                 thrpt   40     270410.755 ±    1818.892  ops/s
   [info] ByteString_slice_Benchmark.bss_drop1                 thrpt   40   10810904.114 ±  298279.889  ops/s
   [info] ByteString_slice_Benchmark.bss_dropRight1            thrpt   40    8685247.609 ±  255687.700  ops/s
   [info] ByteString_slice_Benchmark.bss_greater_or_eq_to_len  thrpt   40  273521343.650 ± 5460020.934  ops/s
   [info] ByteString_slice_Benchmark.bss_iteration             thrpt   40         80.636 ±       1.081  ops/s
   [info] ByteString_slice_Benchmark.bss_iteration2            thrpt   40          6.621 ±       0.034  ops/s
   [info] ByteString_slice_Benchmark.bss_negative              thrpt   40      13245.372 ±      80.026  ops/s
   [info] ByteString_slice_Benchmark.bss_slice_half            thrpt   40      25113.310 ±    1077.999  ops/s
   [info] ByteString_slice_Benchmark.bss_slice_half2           thrpt   40     386212.913 ±   10184.987  ops/s
   [info] ByteString_slice_Benchmark.bss_slice_half3           thrpt   40      47378.585 ±    1710.489  ops/s
   [info] ByteString_slice_Benchmark.bss_take1                 thrpt   40      11416.887 ±     363.871  ops/s
   [info] ByteString_slice_Benchmark.bss_takeRight1            thrpt   40     226438.341 ±    5231.196  ops/s

   --------------------------------- AFTER --------------------------------------------------

   ------ TODAY –––––––
   [info] Benchmark                                             Mode  Cnt          Score         Error  Units
   [info] ByteString_slice_Benchmark.bss_case                  thrpt   40    4966263.298 ±  139761.338  ops/s
   [info] ByteString_slice_Benchmark.bss_case2                 thrpt   40    8678927.639 ±  159740.837  ops/s
   [info] ByteString_slice_Benchmark.bss_case3                 thrpt   40    8960375.387 ±   96679.521  ops/s
   [info] ByteString_slice_Benchmark.bss_case4                 thrpt   40    4443335.581 ±   43210.672  ops/s
   [info] ByteString_slice_Benchmark.bss_drop1                 thrpt   40    3958787.348 ±   67623.829  ops/s
   [info] ByteString_slice_Benchmark.bss_dropRight1            thrpt   40    3204187.452 ±   44179.937  ops/s
   [info] ByteString_slice_Benchmark.bss_greater_or_eq_to_len  thrpt   40  203088545.418 ± 2338789.417  ops/s
   [info] ByteString_slice_Benchmark.bss_iteration             thrpt   40        733.698 ±      10.027  ops/s
   [info] ByteString_slice_Benchmark.bss_iteration2            thrpt   40        843.650 ±      17.940  ops/s
   [info] ByteString_slice_Benchmark.bss_negative              thrpt   40  205334645.483 ± 1314458.684  ops/s
   [info] ByteString_slice_Benchmark.bss_slice_half            thrpt   40    2977058.352 ±   48202.926  ops/s
   [info] ByteString_slice_Benchmark.bss_slice_half2           thrpt   40    1691869.441 ±   26159.817  ops/s
   [info] ByteString_slice_Benchmark.bss_slice_half3           thrpt   40    2295009.457 ±   29994.882  ops/s
   [info] ByteString_slice_Benchmark.bss_take1                 thrpt   40    9017465.502 ±  129463.925  ops/s
   [info] ByteString_slice_Benchmark.bss_takeRight1            thrpt   40    4758854.310 ±  158576.757  ops/s
   */

  @Benchmark
  def bss_negative(): ByteString =
    bss.slice(n_neg - 1, n_neg)

  @Benchmark
  def bss_greater_or_eq_to_len(): ByteString =
    bss.slice(n_greater_or_eq_to_len, n_greater_or_eq_to_len + 1)

  @Benchmark
  def bss_slice_half(): ByteString =
    bss.slice(from_half, until_half)

  @Benchmark
  def bss_slice_half2(): ByteString =
    bss.slice(from_half2, until_half2)

  @Benchmark
  def bss_slice_half3(): ByteString =
    bss.slice(from_half3, until_half3)

  @Benchmark
  def bss_take1(): ByteString =
    bss.slice(from_take1, until_take1)

  @Benchmark
  def bss_dropRight1(): ByteString =
    bss.slice(from_dropRight1, until_dropRight1)

  @Benchmark
  def bss_takeRight1(): ByteString =
    bss.slice(from_takeRight1, until_takeRight1)

  @Benchmark
  def bss_drop1(): ByteString =
    bss.slice(from_drop1, until_drop1)

  @Benchmark
  def bss_case(): ByteString =
    bss.slice(from_case, until_case)

  @Benchmark
  def bss_case2(): ByteString =
    bss.slice(from_case2, until_case2)

  @Benchmark
  def bss_case3(): ByteString =
    bss.slice(from_case3, until_case3)

  @Benchmark
  def bss_case4(): ByteString =
    bss.slice(from_case4, until_case4)

  @Benchmark
  def bss_iteration(): Unit = {
    var i = 0
    while (i < len) {
      bss.slice(i, len)
      i += 1
    }
  }

  @Benchmark
  def bss_iteration2(): Unit = {
    var i = 0
    while (i <= len) {
      bss.slice(0, i)
      i += 1
    }
  }
}
