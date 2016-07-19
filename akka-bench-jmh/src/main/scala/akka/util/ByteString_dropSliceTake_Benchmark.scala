/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.util

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

import akka.util.ByteString.{ ByteString1C, ByteStrings }
import org.openjdk.jmh.annotations._

@State(Scope.Benchmark)
@Measurement(timeUnit = TimeUnit.MILLISECONDS)
class ByteString_dropSliceTake_Benchmark {

  val _bs_mini = ByteString(Array.ofDim[Byte](128 * 4))
  val _bs_small = ByteString(Array.ofDim[Byte](1024 * 1))
  val _bs_large = ByteString(Array.ofDim[Byte](1024 * 4))

  val bs_mini = ByteString(Array.ofDim[Byte](128 * 4 * 4))
  val bs_small = ByteString(Array.ofDim[Byte](1024 * 1 * 4))
  val bs_large = ByteString(Array.ofDim[Byte](1024 * 4 * 4))

  val bss_mini = ByteStrings(Vector.fill(4)(bs_mini.asInstanceOf[ByteString1C].toByteString1), 4 * bs_mini.length)
  val bss_small = ByteStrings(Vector.fill(4)(bs_small.asInstanceOf[ByteString1C].toByteString1), 4 * bs_small.length)
  val bss_large = ByteStrings(Vector.fill(4)(bs_large.asInstanceOf[ByteString1C].toByteString1), 4 * bs_large.length)
  val bss_pc_large = bss_large.compact

  /*
   --------------------------------- BASELINE ------------------------------------------------------------------ 
   [info] Benchmark                                       Mode  Cnt          Score          Error  Units
   [info] ByteString_slice_Benchmark.bs_large_drop_100   thrpt   20  106 827 319.161 ± 20915043.859  ops/s
   [info] ByteString_slice_Benchmark.bs_large_drop_18    thrpt   20  112 421 148.427 ±  8933506.246  ops/s
   [info] ByteString_slice_Benchmark.bss_large_drop_100  thrpt   20    1 008 713.179 ±    77161.075  ops/s
   [info] ByteString_slice_Benchmark.bss_large_drop_18   thrpt   20      844 171.592 ±    63134.617  ops/s
   
   --------------------------------- AFTER ---------------------------------------------------------------------
   [info] Benchmark                                       Mode  Cnt          Score         Error  Units
   [info] ByteString_slice_Benchmark.bss_large_drop_100  thrpt   20  111 537 187.065 ± 2213028.834  ops/s
   [info] ByteString_slice_Benchmark.bss_large_drop_18   thrpt   20  105 968 160.683 ± 2656325.680  ops/s
   
   // after specializing drop and slice inside ByteString1
   [info] ByteString_slice_Benchmark.bs_large_drop_100   thrpt   10  236 727 449.410 ± 7614663.904  ops/s
   [info] ByteString_slice_Benchmark.bs_large_drop_18    thrpt   10  253 912 922.339 ± 4699233.446  ops/s
   [info] ByteString_slice_Benchmark.bss_large_drop_100  thrpt   10   99 900 512.087 ± 2996994.941  ops/s
   [info] ByteString_slice_Benchmark.bss_large_drop_18   thrpt   10   93 545 294.152 ± 8028975.108  ops/s
   
   // after specialized slice / drop / dropRight
   
   [info] Benchmark                                           Mode  Cnt          Score         Error  Units
   [info] ByteString_slice_Benchmark.bs_large_drop_100       thrpt   10  145 157 309.355 ± 3309390.725  ops/s
   [info] ByteString_slice_Benchmark.bs_large_drop_18        thrpt   10  145 849 413.233 ± 3239930.569  ops/s
   [info] ByteString_slice_Benchmark.bss_large_drop_100      thrpt   10  109 700 733.604 ± 2455644.974  ops/s
   [info] ByteString_slice_Benchmark.bss_large_drop_18       thrpt   10  104 343 810.847 ± 3532601.254  ops/s
   
   
   BEFORE ADDING SLICE/DROP/DROPRIGHT to ByteStrings
   [info] Benchmark                                            Mode  Cnt          Score          Error  Units
   [info] ByteString_slice_Benchmark.bs_large_slice_129_129   thrpt   20  165 283 693.646 ± 58372532.116  ops/s
   [info] ByteString_slice_Benchmark.bs_large_slice_80_80     thrpt   20  149 181 442.446 ± 43824223.989  ops/s
   [info] ByteString_slice_Benchmark.bss_large_slice_129_129  thrpt   20    7 908 720.458 ±   447009.623  ops/s
   [info] ByteString_slice_Benchmark.bss_large_slice_80_80    thrpt   20    8 005 069.269 ±   449723.994  ops/s
   
   AFTER
   [info] ByteString_slice_Benchmark.bs_large_slice_129_129   thrpt   20  223 874 369.668 ±  8123824.754  ops/s
   [info] ByteString_slice_Benchmark.bs_large_slice_80_80     thrpt   20  143 057 484.420 ± 34627584.378  ops/s
   [info] ByteString_slice_Benchmark.bss_large_slice_129_129  thrpt   20    4 077 613.279 ±   212111.191  ops/s
   [info] ByteString_slice_Benchmark.bss_large_slice_80_80    thrpt   20    3 962 181.226 ±   312126.706  ops/s
   
   SPECIALIZED DROP IN BYTESTRINGS
   [info] Benchmark                                           Mode  Cnt          Score          Error  Units
   [info] ByteString_slice_Benchmark.bs_large_drop_18        thrpt   20  112 869 365.543 ±  4626482.567  ops/s
   [info] ByteString_slice_Benchmark.bs_large_drop_100       thrpt   20  114 158 189.654 ± 15746992.544  ops/s
   [info] ByteString_slice_Benchmark.bss_large_drop_100      thrpt   20    2 798 201.039 ±   180478.001  ops/s
   [info] ByteString_slice_Benchmark.bss_large_drop_18       thrpt   20    2 295 888.570 ±   272181.184  ops/s
   
   
   ----- MONDAY -----
   before:
   [info] ByteString_slice_Benchmark.bss_large_drop_18       thrpt   40  1 094 991.579 ±  69480.051  ops/s
   [info] ByteString_slice_Benchmark.bss_large_drop_100      thrpt   40  1 073 945.857 ± 116307.385  ops/s
   [info] ByteString_slice_Benchmark.bss_large_drop_256      thrpt   40  1 117 367.822 ±  16507.998  ops/s
   

   after 1:
   [info] ByteString_slice_Benchmark.bss_large_drop_100      thrpt   40  2 749 448.915 ± 146322.032  ops/s
   [info] ByteString_slice_Benchmark.bss_large_drop_18       thrpt   40  2 912 894.125 ±  29794.683  ops/s
   [info] ByteString_slice_Benchmark.bss_large_drop_256      thrpt   40  2 919 287.834 ±  39135.615  ops/s
   
   after 2, hardcore while:
   [info] ByteString_slice_Benchmark.bss_large_drop_100      thrpt   40  11 167 024.102 ± 138395.402  ops/s
   [info] ByteString_slice_Benchmark.bss_large_drop_18       thrpt   40  11 153 933.788 ± 282706.017  ops/s
   [info] ByteString_slice_Benchmark.bss_large_drop_256      thrpt   40  11 221 261.775 ± 128652.997  ops/s
   
   
   ------ TODAY –––––––
   [info] Benchmark                                                 Mode  Cnt          Score          Error  Units
   [info] ByteString_slice_Benchmark.bs_large_dropRight_100        thrpt   20  136 937 868.648 ±  4159980.895  ops/s
   [info] ByteString_slice_Benchmark.bs_large_dropRight_2000       thrpt   20  141 033 350.918 ±  2085416.316  ops/s
   [info] ByteString_slice_Benchmark.bs_large_dropRight_256        thrpt   20  141 226 984.797 ±  2646640.834  ops/s
   [info] ByteString_slice_Benchmark.bs_large_drop_100             thrpt   20  139 023 728.986 ±  1908998.682  ops/s
   [info] ByteString_slice_Benchmark.bs_large_drop_2000            thrpt   20  138 422 208.316 ±  1553476.775  ops/s
   [info] ByteString_slice_Benchmark.bs_large_drop_256             thrpt   20  131 607 893.667 ±  2583593.762  ops/s
   [info] ByteString_slice_Benchmark.bs_large_slice_129_129        thrpt   20  280 713 290.697 ±  5341571.982  ops/s
   [info] ByteString_slice_Benchmark.bs_large_slice_80_80          thrpt   20  249 472 120.921 ±  3138455.875  ops/s
   [info] ByteString_slice_Benchmark.bss_large_dropRight_100       thrpt   20   11 041 701.080 ±   130031.483  ops/s
   [info] ByteString_slice_Benchmark.bss_large_dropRight_2000      thrpt   20   10 816 061.052 ±   146001.182  ops/s
   [info] ByteString_slice_Benchmark.bss_large_dropRight_256       thrpt   20   10 718 409.697 ±   150644.172  ops/s
   [info] ByteString_slice_Benchmark.bss_large_drop_100            thrpt   20   11 606 177.420 ±   329673.410  ops/s
   [info] ByteString_slice_Benchmark.bss_large_drop_18             thrpt   20   11 798 678.009 ±   217022.213  ops/s
   [info] ByteString_slice_Benchmark.bss_large_drop_2000           thrpt   20   11 961 940.752 ±   121615.303  ops/s
   [info] ByteString_slice_Benchmark.bss_large_drop_256            thrpt   20   10 816 775.568 ±   356888.715  ops/s
   [info] ByteString_slice_Benchmark.bss_large_slice_129_129       thrpt   20  154 058 292.128 ± 34843978.776  ops/s
   [info] ByteString_slice_Benchmark.bss_large_slice_80_80         thrpt   20  264 803 380.989 ±  4315065.796  ops/s
   
   */

  // 18 == "http://example.com", a typical url length 

  def bs_large_drop_18: ByteString =
    bs_large.drop(18)
  @Benchmark
  def bss_large_drop_18: ByteString =
    bss_large.drop(18)

  @Benchmark
  def bs_large_drop_100: ByteString =
    bs_large.drop(100)
  @Benchmark
  def bss_large_drop_100: ByteString =
    bss_large.drop(100)

  @Benchmark
  def bs_large_drop_256: ByteString =
    bs_large.drop(256)
  @Benchmark
  def bss_large_drop_256: ByteString =
    bss_large.drop(256)

  @Benchmark
  def bs_large_drop_2000: ByteString =
    bs_large.drop(2000)
  @Benchmark
  def bss_large_drop_2000: ByteString =
    bss_large.drop(2000)

  /* these force 2 array drops, and 1 element drop inside the 2nd to first/last; can be considered as "bad case" */

  @Benchmark
  def bs_large_slice_129_129: ByteString =
    bs_large.slice(129, 129)
  @Benchmark
  def bss_large_slice_129_129: ByteString =
    bss_large.slice(129, 129)

  /* these only move the indexes, don't drop any arrays "happy case" */

  @Benchmark
  def bs_large_slice_80_80: ByteString =
    bs_large.slice(80, 80)
  @Benchmark
  def bss_large_slice_80_80: ByteString =
    bss_large.slice(80, 80)

  // drop right ---

  @Benchmark
  def bs_large_dropRight_100: ByteString =
    bs_large.dropRight(100)
  @Benchmark
  def bss_large_dropRight_100: ByteString =
    bss_large.dropRight(100)

  @Benchmark
  def bs_large_dropRight_256: ByteString =
    bs_large.dropRight(256)
  @Benchmark
  def bss_large_dropRight_256: ByteString =
    bss_large.dropRight(256)

  @Benchmark
  def bs_large_dropRight_2000: ByteString =
    bs_large.dropRight(2000)
  @Benchmark
  def bss_large_dropRight_2000: ByteString =
    bss_large.dropRight(2000)

}
