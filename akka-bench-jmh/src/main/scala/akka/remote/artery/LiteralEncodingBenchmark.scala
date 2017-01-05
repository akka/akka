/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations._

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput))
@Fork(2)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
class LiteralEncodingBenchmark {

  private val UsAscii = Charset.forName("US-ASCII")
  private val str = "akka://SomeSystem@host12:1234/user/foo"
  private val buffer = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
  private val literalChars = Array.ofDim[Char](64)
  private val literalBytes = Array.ofDim[Byte](64)
  private val unsafe = akka.util.Unsafe.instance
  private val stringValueFieldOffset = unsafe.objectFieldOffset(classOf[String].getDeclaredField("value"))

  @Benchmark
  def getBytesNewArray(): String = {
    val length = str.length()
    // write
    buffer.clear()
    val bytes = str.getBytes(UsAscii)
    buffer.put(bytes)
    buffer.flip()

    // read
    val bytes2 = Array.ofDim[Byte](length)
    buffer.get(bytes2)
    new String(bytes2, UsAscii)
  }

  @Benchmark
  def getBytesReuseArray(): String = {
    val length = str.length()
    // write
    buffer.clear()
    val bytes = str.getBytes(UsAscii)
    buffer.put(bytes)
    buffer.flip()

    // read
    buffer.get(literalBytes, 0, length)
    new String(literalBytes, UsAscii)
  }

  @Benchmark
  def getChars(): String = {
    val length = str.length()
    // write
    buffer.clear()
    str.getChars(0, length, literalChars, 0)
    var i = 0
    while (i < length) {
      literalBytes(i) = literalChars(i).asInstanceOf[Byte]
      i += 1
    }
    buffer.put(literalBytes, 0, length)
    buffer.flip()

    // read
    buffer.get(literalBytes, 0, length)
    i = 0
    while (i < length) {
      // UsAscii
      literalChars(i) = literalBytes(i).asInstanceOf[Char]
      i += 1
    }
    String.valueOf(literalChars, 0, length)
  }

  @Benchmark
  def getCharsUnsafe(): String = {
    val length = str.length()
    // write
    buffer.clear()
    val chars = unsafe.getObject(str, stringValueFieldOffset).asInstanceOf[Array[Char]]
    var i = 0
    while (i < length) {
      literalBytes(i) = chars(i).asInstanceOf[Byte]
      i += 1
    }
    buffer.put(literalBytes, 0, length)
    buffer.flip()

    // read
    buffer.get(literalBytes, 0, length)
    i = 0
    while (i < length) {
      // UsAscii
      literalChars(i) = literalBytes(i).asInstanceOf[Char]
      i += 1
    }
    String.valueOf(literalChars, 0, length)
  }

}
