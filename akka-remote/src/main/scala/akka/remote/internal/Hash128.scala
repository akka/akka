/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.internal

import java.lang.{ Long => JLong }

import scala.annotation.switch

object Hash128 {

  private val C1 = 0x87C37B91114253D5L
  private val C2 = 0x4CF5AD432745937FL
  private val R1 = 31
  private val R2 = 27
  private val R3 = 33
  private val M = 5
  private val N1 = 0x52dce729
  private val N2 = 0x38495ab5

  /**
   * Generates 128-bit hash from the byte array.
   *
   * This is an implementation of the 128-bit hash function `MurmurHash3_x64_128`
   * from Austin Appleby's original MurmurHash3 `c++` code in SMHasher.
   */
  def hash128x64(data: Array[Byte]): (Long, Long) =
    hash128x64(data, 0, data.length, 0)

  /**
   * Generates 128-bit hash from the byte array with the given offset, length and seed.
   *
   * This is an implementation of the 128-bit hash function `MurmurHash3_x64_128`
   * from Austin Appleby's original MurmurHash3 `c++` code in SMHasher.
   */
  def hash128x64(data: Array[Byte], offset: Int, length: Int, seed: Int): (Long, Long) = {
    // Use an unsigned 32-bit integer as the seed
    hash128x64Internal(data, offset, length, seed & 0xFFFFFFFFL)
  }

  /**
   * Generates 128-bit hash from the byte array with the given offset, length and seed.
   *
   * This is an implementation of the 128-bit hash function `MurmurHash3_x64_128`
   * from Austin Appleby's original MurmurHash3 `c++` code in SMHasher.
   */
  private def hash128x64Internal(data: Array[Byte], offset: Int, length: Int, seed: Long): (Long, Long) = {
    var h1 = seed
    var h2 = seed
    val nblocks = length >> 4

    // body
    for (i <- 0 until nblocks) {
      val index = offset + (i << 4)
      var k1 = getLittleEndianLong(data, index)
      var k2 = getLittleEndianLong(data, index + 8)

      // mix functions for k1
      k1 *= C1
      k1 = JLong.rotateLeft(k1, R1)
      k1 *= C2
      h1 ^= k1
      h1 = JLong.rotateLeft(h1, R2)
      h1 += h2
      h1 = h1 * M + N1

      // mix functions for k2
      k2 *= C2
      k2 = JLong.rotateLeft(k2, R3)
      k2 *= C1
      h2 ^= k2
      h2 = JLong.rotateLeft(h2, R1)
      h2 += h1
      h2 = h2 * M + N2
    }

    // tail
    var k1 = 0L
    var k2 = 0L
    val index = offset + (nblocks << 4)
    (offset + length - index: @switch) match {
      case 15 =>
        k2 ^= (data(index + 14).toLong & 0xff) << 48
      case 14 =>
        k2 ^= (data(index + 13).toLong & 0xff) << 40
      case 13 =>
        k2 ^= (data(index + 12).toLong & 0xff) << 32
      case 12 =>
        k2 ^= (data(index + 11).toLong & 0xff) << 24
      case 11 =>
        k2 ^= (data(index + 10).toLong & 0xff) << 16
      case 10 =>
        k2 ^= (data(index + 9).toLong & 0xff) << 8
      case 9 =>
        k2 ^= data(index + 8) & 0xff
        k2 *= C2
        k2 = JLong.rotateLeft(k2, R3)
        k2 *= C1
        h2 ^= k2
      case 8 =>
        k1 ^= (data(index + 7).toLong & 0xff) << 56
      case 7 =>
        k1 ^= (data(index + 6).toLong & 0xff) << 48
      case 6 =>
        k1 ^= (data(index + 5).toLong & 0xff) << 40
      case 5 =>
        k1 ^= (data(index + 4).toLong & 0xff) << 32
      case 4 =>
        k1 ^= (data(index + 3).toLong & 0xff) << 24
      case 3 =>
        k1 ^= (data(index + 2).toLong & 0xff) << 16
      case 2 =>
        k1 ^= (data(index + 1).toLong & 0xff) << 8
      case 1 =>
        k1 ^= data(index) & 0xff
        k1 *= C1
        k1 = JLong.rotateLeft(k1, R1)
        k1 *= C2
        h1 ^= k1
    }
    
    // finalization
    h1 ^= length
    h2 ^= length
    h1 += h2
    h2 += h1
    h1 = fmix64(h1)
    h2 = fmix64(h2)
    h1 += h2
    h2 += h1

    (h1, h2)
  }

  /**
   * Gets the little-endian long from 8 bytes starting at the specified index.
   *
   * @param data  The data
   * @param index The index
   * @return The little-endian long
   */
  private def getLittleEndianLong(data: Array[Byte], index: Int): Long = {
    data(index).toLong & 0xff | (data(index + 1).toLong & 0xff) << 8 | (data(index + 2).toLong & 0xff) << 16 | (data(
      index + 3).toLong & 0xff) << 24 | (data(index + 4).toLong & 0xff) << 32 | (data(index + 5).toLong & 0xff) << 40 | (data(
      index + 6).toLong & 0xff) << 48 | (data(index + 7).toLong & 0xff) << 56
  }

  /**
   * Performs the final avalanche mix step of the 128-bit hash function `hash128x64Internal`.
   *
   * @param hash The current hash
   * @return The final hash
   */
  private def fmix64(hash: Long): Long = {
    var h = hash
    h ^= h >>> 33
    h *= 0xFF51AFD7ED558CCDL
    h ^= h >>> 33
    h *= 0xC4CEB9FE1A85EC53L
    h ^= h >>> 33
    h
  }

}
