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
class ByteString_append_Benchmark {

  val str = List.fill[Byte](4)(0).mkString
  val numVec = 1024
  val bss_small = ByteStrings(Vector.fill(numVec / 32)(ByteString1.fromString(str)))
  val bss_middle = ByteStrings(Vector.fill(numVec / 2)(ByteString1.fromString(str)))
  val bss = ByteStrings(Vector.fill(numVec)(ByteString1.fromString(str)))

  /*
   --------------------------------- BASELINE -------------------------------------------------------------------
   commit 0f2da7b26b5c4af35be87d2bd4a1a2392365df15
   [info] Benchmark                                             Mode  Cnt           Score          Error  Units
   [info] ByteString_take_Benchmark.bss_avg                   thrpt   40       28710.870 ±      437.608  ops/s
   [info] ByteString_take_Benchmark.bss_best                  thrpt   40    20987018.075 ±   443608.693  ops/s
   [info] ByteString_take_Benchmark.bss_greater_or_eq_to_len  thrpt   40   894573619.213 ±  4360367.026  ops/s
   [info] ByteString_take_Benchmark.bss_negative              thrpt   40  1164398934.041 ± 15083443.165  ops/s
   [info] ByteString_take_Benchmark.bss_worst                 thrpt   40       11936.857 ±      373.828  ops/s

   --------------------------------- AFTER ----------------------------------------------------------------------

   ------ TODAY –––––––
   [info] Benchmark                                             Mode  Cnt           Score          Error  Units
   [info] ByteString_take_Benchmark.bss_avg                   thrpt   40      539211.297 ±     9073.181  ops/s
   [info] ByteString_take_Benchmark.bss_best                  thrpt   40   197237882.251 ±  2714956.732  ops/s
   [info] ByteString_take_Benchmark.bss_greater_or_eq_to_len  thrpt   40   866558812.838 ± 15343155.818  ops/s
   [info] ByteString_take_Benchmark.bss_negative              thrpt   40  1114723770.487 ± 30945339.512  ops/s
   [info] ByteString_take_Benchmark.bss_worst                 thrpt   40      233870.585 ±     7326.227  ops/s

   */

  @Benchmark
  def bss_append_small(): ByteString =
    bss_small ++ bss_small

  @Benchmark
  def bss_append_middle(): ByteString =
    bss_middle ++ bss_middle

  @Benchmark
  def bss_append_large(): ByteString =
    bss ++ bss
}
