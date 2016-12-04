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
class ByteString_take_Benchmark {

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
   --------------------------------- BASELINE -------------------------------------------------------------------
   commit 0f2da7b26b5c4af35be87d2bd4a1a2392365df15
   [info] Benchmark                                            Mode  Cnt          Score          Error  Units
   [info] ByteString_take_Benchmark.bss_avg                   thrpt   40      28019.740 ±     1175.456  ops/s
   [info] ByteString_take_Benchmark.bss_best                  thrpt   40   20326905.468 ±   889508.335  ops/s
   [info] ByteString_take_Benchmark.bss_greater_or_eq_to_len  thrpt   40  324506235.049 ± 12040396.971  ops/s
   [info] ByteString_take_Benchmark.bss_iteration             thrpt   40          6.890 ±        0.140  ops/s
   [info] ByteString_take_Benchmark.bss_negative              thrpt   40  295420920.031 ±  4201642.454  ops/s
   [info] ByteString_take_Benchmark.bss_worst                 thrpt   40      13378.362 ±      450.396  ops/s

   --------------------------------- AFTER ----------------------------------------------------------------------

   ------ TODAY –––––––
   [info] Benchmark                                            Mode  Cnt          Score          Error  Units
   [info] ByteString_take_Benchmark.bss_avg                   thrpt   40    6658796.409 ±   147086.276  ops/s
   [info] ByteString_take_Benchmark.bss_best                  thrpt   40   17825424.621 ±   213434.831  ops/s
   [info] ByteString_take_Benchmark.bss_greater_or_eq_to_len  thrpt   40  325472727.077 ± 10478779.758  ops/s
   [info] ByteString_take_Benchmark.bss_iteration             thrpt   40       1162.864 ±       28.621  ops/s
   [info] ByteString_take_Benchmark.bss_negative              thrpt   40  297056629.331 ±  4105606.094  ops/s
   [info] ByteString_take_Benchmark.bss_worst                 thrpt   40    4357243.962 ±   133106.265  ops/s
   */

  @Benchmark
  def bss_negative(): ByteString =
    bss.take(n_neg)

  @Benchmark
  def bss_greater_or_eq_to_len(): ByteString =
    bss.take(n_greater_or_eq_to_len)

  @Benchmark
  def bss_avg(): ByteString =
    bss.take(n_avg)

  @Benchmark
  def bss_best(): ByteString =
    bss.take(n_best)

  @Benchmark
  def bss_worst(): ByteString =
    bss.take(n_worst)

  @Benchmark
  def bss_iteration(): Unit = {
    var i = 0
    while (i < len) {
      bss.take(i)
      i += 1
    }
  }
}
