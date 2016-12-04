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
class ByteString_dropRight_Benchmark {

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
   --------------------------------- BASELINE -----------------------------------------------------------------------
   commit 0f2da7b26b5c4af35be87d2bd4a1a2392365df15
   [info] Benchmark                                                 Mode  Cnt          Score         Error  Units
   [info] ByteString_dropRight_Benchmark.bss_avg                   thrpt   40      27775.584 ±     153.671  ops/s
   [info] ByteString_dropRight_Benchmark.bss_best                  thrpt   40    8896435.523 ±   39693.086  ops/s
   [info] ByteString_dropRight_Benchmark.bss_greater_or_eq_to_len  thrpt   40      13166.278 ±     149.709  ops/s
   [info] ByteString_dropRight_Benchmark.bss_iteration             thrpt   40          6.361 ±       0.030  ops/s
   [info] ByteString_dropRight_Benchmark.bss_negative              thrpt   40  348765581.301 ± 5981494.625  ops/s
   [info] ByteString_dropRight_Benchmark.bss_worst                 thrpt   40      13364.024 ±      69.827  ops/s

   --------------------------------- AFTER --------------------------------------------------------------------------

   ------ TODAY –––––––
   [info] Benchmark                                                 Mode  Cnt          Score         Error  Units
   [info] ByteString_dropRight_Benchmark.bss_avg                   thrpt   40    5546191.528 ±  129660.665  ops/s
   [info] ByteString_dropRight_Benchmark.bss_best                  thrpt   40    3781902.387 ±   71481.259  ops/s
   [info] ByteString_dropRight_Benchmark.bss_greater_or_eq_to_len  thrpt   40  254119811.602 ± 3464120.268  ops/s
   [info] ByteString_dropRight_Benchmark.bss_iteratio              thrpt   40        975.490 ±      21.433  ops/s
   [info] ByteString_dropRight_Benchmark.bss_negative              thrpt   40  275402741.230 ± 8882548.856  ops/s
   [info] ByteString_dropRight_Benchmark.bss_worst                 thrpt   40   16023701.310 ±  159979.511  ops/s
   */

  @Benchmark
  def bss_negative(): ByteString =
    bss.dropRight(n_neg)

  @Benchmark
  def bss_greater_or_eq_to_len(): ByteString =
    bss.dropRight(n_greater_or_eq_to_len)

  @Benchmark
  def bss_avg(): ByteString =
    bss.dropRight(n_avg)

  @Benchmark
  def bss_best(): ByteString =
    bss.dropRight(n_best)

  @Benchmark
  def bss_worst(): ByteString =
    bss.dropRight(n_worst)

  @Benchmark
  def bss_iteration(): Unit = {
    var i = 0
    while (i < len) {
      bss.dropRight(i)
      i += 1
    }
  }
}
