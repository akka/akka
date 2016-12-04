/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.util

import java.io.{ ObjectInputStream, ObjectOutputStream }
import java.nio.{ ByteBuffer, ByteOrder }
import java.lang.{ Iterable ⇒ JIterable }

import scala.annotation.{ tailrec, varargs }
import scala.collection.IndexedSeqOptimized
import scala.collection.mutable.{ Builder, WrappedArray }
import scala.collection.immutable
import scala.collection.immutable.{ IndexedSeq, VectorBuilder, VectorIterator }
import scala.collection.generic.CanBuildFrom
import scala.reflect.ClassTag
import java.nio.charset.{ Charset, StandardCharsets }

object ByteString {

  /**
   * Creates a new ByteString by copying a byte array.
   */
  def apply(bytes: Array[Byte]): ByteString = CompactByteString(bytes)

  /**
   * Creates a new ByteString by copying bytes.
   */
  def apply(bytes: Byte*): ByteString = CompactByteString(bytes: _*)

  /**
   * Creates a new ByteString by converting from integral numbers to bytes.
   */
  def apply[T](bytes: T*)(implicit num: Integral[T]): ByteString =
    CompactByteString(bytes: _*)(num)

  /**
   * Creates a new ByteString by copying bytes from a ByteBuffer.
   */
  def apply(bytes: ByteBuffer): ByteString = CompactByteString(bytes)

  /**
   * Creates a new ByteString by encoding a String as UTF-8.
   */
  def apply(string: String): ByteString = apply(string, UTF_8)

  /**
   * Creates a new ByteString by encoding a String with a charset.
   */
  def apply(string: String, charset: String): ByteString = CompactByteString(string, charset)

  /**
   * Creates a new ByteString by copying a byte array.
   */
  def fromArray(array: Array[Byte]): ByteString = apply(array)

  /**
   * Creates a new ByteString by copying length bytes starting at offset from
   * an Array.
   */
  def fromArray(array: Array[Byte], offset: Int, length: Int): ByteString =
    CompactByteString.fromArray(array, offset, length)

  /**
   * JAVA API
   * Creates a new ByteString by copying an int array by converting from integral numbers to bytes.
   */
  @varargs
  def fromInts(array: Int*): ByteString =
    apply(array: _*)(scala.math.Numeric.IntIsIntegral)

  /**
   * Creates a new ByteString which will contain the UTF-8 representation of the given String
   */
  def fromString(string: String): ByteString = apply(string)

  /**
   * Creates a new ByteString which will contain the representation of the given String in the given charset
   */
  def fromString(string: String, charset: String): ByteString = apply(string, charset)

  /**
   * Standard "UTF-8" charset
   */
  val UTF_8: String = StandardCharsets.UTF_8.name()

  /**
   * Creates a new ByteString by copying bytes out of a ByteBuffer.
   */
  def fromByteBuffer(buffer: ByteBuffer): ByteString = apply(buffer)

  val empty: ByteString = CompactByteString(Array.empty[Byte])

  def newBuilder: ByteStringBuilder = new ByteStringBuilder

  /** Java API */
  def createBuilder: ByteStringBuilder = new ByteStringBuilder

  implicit val canBuildFrom: CanBuildFrom[TraversableOnce[Byte], Byte, ByteString] =
    new CanBuildFrom[TraversableOnce[Byte], Byte, ByteString] {
      def apply(ignore: TraversableOnce[Byte]): ByteStringBuilder = newBuilder
      def apply(): ByteStringBuilder = newBuilder
    }

  private[akka] object ByteString1C extends Companion {
    def fromString(s: String): ByteString1C = new ByteString1C(s.getBytes)
    def apply(bytes: Array[Byte]): ByteString1C = new ByteString1C(bytes)
    val SerializationIdentity = 1.toByte

    def readFromInputStream(is: ObjectInputStream): ByteString1C = {
      val length = is.readInt()
      val arr = new Array[Byte](length)
      is.readFully(arr, 0, length)
      ByteString1C(arr)
    }
  }

  private[akka] object BinaryIndexedTree {
    // FixMe: What should we have for sumTable and elemTable? Array(0), null, or something else?
    val empty = BinaryIndexedTree(0, 0, 0, 0, Array(0), Array(0))

    def apply(strings: Vector[ByteString1]): BinaryIndexedTree = {
      val elemTable = createElemTable(strings)
      val sumTable = createSumTable(elemTable)
      BinaryIndexedTree(0, 0, strings.length, 0, sumTable, elemTable)
    }

    private def createElemTable(strings: Vector[ByteString1]): Array[Int] = {
      val size = strings.size
      val elemTable = new Array[Int](size)
      var i = 0
      while (i < size) {
        elemTable(i) = strings(i).length
        i += 1
      }
      elemTable
    }

    private[akka] def createSumTable(elemTable: Array[Int]): Array[Int] = {
      val tableSize = elemTable.size
      val sumTable = new Array[Int](tableSize)
      System.arraycopy(elemTable, 0, sumTable, 0, tableSize)

      var oneBasedIdx = 2
      while (oneBasedIdx <= tableSize) {
        var i = 1
        while (oneBasedIdx * i <= tableSize) {
          val idx = oneBasedIdx * i
          sumTable(idx - 1) += sumTable(idx - oneBasedIdx / 2 - 1) // add up sub-sum of sumTable(idx-1)
          i += 1
        }
        oneBasedIdx *= 2
      }

      sumTable
    }
  }

  /**
   * Binary Indexed Tree(BIT) with some extension to store `length` of each of the ByteStrings' internal strings
   *
   * [Basics]
   * The basic data structure of BIT is as follows;
   * Suppos we have a ByteString that has 8 internal strings. To make the example simple, we assume each string has
   * the length of 1.
   *
   * 1) All the odd-index element represetnts it's corresponding string's length.
   *    Each of the 1st, 3rd, 5th and 7th element represents it's length 1.
   *
   * 2) Other elements represent sum of itself and other elements.
   *    For example, index of power of 2 contain the sum between 1 to itself:
   *      2nd index => 1(1st elem) + 1(2nd string)
   *      4th index => 2(2nd elem) + 1(3rd elem) + 1(4th string)
   *      8th index => 4(4th elem) + 2(6th elem) + 1(7th elem) + 1(8th string)
   *    Note that some elements represent the _partial_ sum:
   *      6th index => 1(5th elem) + 1(6th string)
   *
   * 3) Complexity
   *    3-1) Space complexity: O(N) = exactly N where N is the number of strings
   *    3-2) Time complexity:
   *      create BIT (using an array representing internal string lengths): O(N) = almost 2*N => N + N/2 + N/4 + ...
   *      find lower bound of 'n': O(log(N))
   *      take(n) / drop(n) / slice(start, until) : O(log(N))
   *
   * < Visual aid of BIT: each number represetns 1-based index >
   * +-----------------------------------------+
   * |                    8                    |
   * +-----------------------------------------+
   * +--------------------+
   * |         4          |
   * +--------------------+
   * +---------+            +---------+
   * |    2    |            |    6    |
   * +---------+            +---------+
   * +---+      +---+       +---+      +---+
   * | 1 |      | 3 |       | 5 |      | 7 |
   * +---+      +---+       +---+      +---+
   *
   * < Visual aid of BIT: each number represents the actual element contained in each indice >
   * +-----------------------------------------+
   * |                    8                    |
   * +-----------------------------------------+
   * +--------------------+
   * |         4          |
   * +--------------------+
   * +---------+            +---------+
   * |    2    |            |    2    |
   * +---------+            +---------+
   * +---+      +---+       +---+      +---+
   * | 1 |      | 1 |       | 1 |      | 1 |
   * +---+      +---+       +---+      +---+
   *
   * In our BinaryIndexTree, sumTable works as a basic BIT. To achieve a more efficient BIT, we'll extend this basics as follows.
   *
   * [Extension]
   * 1)
   * In addtion to the basic mechanism of BIT, we have some metadata;
   *   - fullDrops indicates the number of (BIT) elements to drop starting from the 1st index.
   *   - restToDrop indicates the number of length to drop at index _fullDrops_.
   *   - fullTakes indicates the number of (BIT) elements to take starting from the 1st index.
   *   - restToTake indicates the number of length to additionally take at index _fullTakes_.
   *
   * As we do some operation, such as take(n) and drop(n), we MUST maintain BIT that correctly describes ByteString's internal
   * string lengths. If we recreate BIT itself, which is sumTable(Array[Int]) in our case, it takes time more than we expect.
   *
   * Instead of reconstructing sumTable(Array[Int]) everytime we do these operations, we can reuse it and update the metadata. This leads
   * to an efficient BIT manipulation, then we can expect performance increase. This is where our metadata comes into play.
   * Suppose we have ByteString with 4 internal strings, each of which has the length of 1, 2, 3 and 4, then we have;
   *
   * < Visual aid of sumTable: each number represetns the actual element contained in each indice >
   * +--------------------+
   * |        10          |
   * +--------------------+
   * +---------+
   * |    3    |
   * +---------+
   * +---+      +---+
   * | 1 |      | 3 |
   * +---+      +---+
   *
   * Initially we have the following metadata.
   *   - fullDrops  = 0 as we don't have to drop anything
   *   - restToDrop = 0 as we don't have to drop anything
   *   - fullTakes  = 4 as we have 4 elements in sumTable
   *   - restToDrop = 0 as we don't have any additional elements to take (in addition to fullTakes)
   *
   * 1-1) If we drop(1), we just need to _drop_ the first whole element from sumTable.
   *      Even we don't recreate sumTable, we can still have the correct BIT information
   *      by updating fullDrops from 0 to 1 and keeping the same sumTable.
   *
   * +----|----------------+
   * |    |    10          |
   * +----|----------------+
   * +----|-----+
   * | 1  |  2  |
   * +----|-----+
   * +---+|      +---+
   * | 1 ||      | 3 |
   * +---+|      +---+
   *
   * 1-2) If we take(2), we need to take the first whole element AND one additional element from the second element.
   *      Again we can just reuse the same sumTable and update the metadata(fullTakes from 4 to 1 and restToTake from 0 to 1)
   *
   * +-------|--------------+
   * |       |  10          |
   * +-------|--------------+
   * +---|---|---+
   * | 1   1 | 1 |
   * +---|---|---+
   * +---+   |    +---+
   * | 1 |   |    | 3 |
   * +---+   |    +---+
   *
   * 2) elemTable
   * In addition to these metadata, we have another piece of information that contains lengths of internal strings.
   * As it's a little bit costly to calculate i-th element from BIT(sumTable), whose time complexity is O(log(N)),
   * it takes time to access to i-th elem. So we'd better have this additional information (with space compelexity O(N)).
   *
   * Time complexity:
   *   access to i-th elem: O(1)
   * Space complexity:
   *   O(N)
   *
   *
   * Detailed explanation of the params below is given above.
   * @param fullDrops  Number of (BIT) elements to drop starting from the 1st index.
   * @param restToDrop Number of length to drop at index _fullDrops_
   * @param fullTakes  Number of (BIT) elements to take starting from the 1st index
   * @param restToTake Number of length to additionally take at index _fullTakes_
   * @param sumTable   Basic BIT to represent whole ByteString internal string lengths
   * @param elemTable  Array to represent each length of internal strings
   *                   FIXME: what would be the better name? _lengths_ whould be better?
   */
  private[akka] case class BinaryIndexedTree(fullDrops: Int, restToDrop: Int, fullTakes: Int, restToTake: Int, sumTable: Array[Int], elemTable: Array[Int]) {

    def isEmpty: Boolean = fullDrops == fullTakes && restToDrop == restToTake

    /**
     * This is a helper function to perform take(n), drop(n) and slice(start, until) efficiently.
     * - The basic concept is similar to C++ Vector's lower_bound() for example.
     * - Find the lower bound of _n_, in other words find the BIT index that represent _n_ length
     * starting from the _current_ head.
     * - fullDrops and restToDrop is considered to calculate the index.
     */
    private[akka] def findLowerBoundIndexByNumberOfElements(numElems: Int): Int = {
      val numDrops = addUpTo(fullDrops) + restToDrop
      @tailrec def findLowrBound(low: Int, high: Int): Int = {
        if (high - low > 1) {
          val mid = (high + low) / 2
          val sumToMid = addUpTo(mid + 1) - numDrops
          if (sumToMid >= numElems) findLowrBound(low, mid)
          else findLowrBound(mid, high)
        } else high
      }

      val low = fullDrops - 1
      val high = if (restToTake == 0) fullTakes else fullTakes + 1
      findLowrBound(low, high)
    }

    /**
     * Return the sum between the index 1 to n where index is 1-based.
     */
    private[this] def addUpTo(oneBasedIdx: Int): Int = {
      @tailrec def addUpToI(acc: Int, i: Int): Int = {
        if (i > 0) addUpToI(acc + sumTable(i - 1), i & (i - 1))
        else acc
      }
      addUpToI(0, oneBasedIdx)
    }

    // Total length of internal strings
    // FixMe: What is the better variable name?
    private[this] val total =
      if (isEmpty) {
        0
      } else {
        val takes = addUpTo(fullTakes) + restToTake
        val drops = addUpTo(fullDrops) + restToDrop
        takes - drops
      }

    def take(n: Int): BinaryIndexedTree =
      if (n <= 0) BinaryIndexedTree.empty
      else if (n >= total) this
      else take1(n)

    private[this] def take1(n: Int): BinaryIndexedTree = {
      val total = addUpTo(fullDrops) + restToDrop + n
      val bound = findLowerBoundIndexByNumberOfElements(n)
      val sum = addUpTo(bound + 1)
      val newFullTakes = if (sum == total) bound + 1 else bound
      var newRestToTake = if (sum == total) 0 else total - addUpTo(bound)

      if (newFullTakes == fullTakes)
        newRestToTake = math.min(newRestToTake, restToTake)

      BinaryIndexedTree(fullDrops, restToDrop, newFullTakes, newRestToTake, sumTable, elemTable)
    }

    def drop(n: Int): BinaryIndexedTree =
      if (n <= 0) this
      else if (n >= total) BinaryIndexedTree.empty
      else drop1(n)

    private[this] def drop1(n: Int): BinaryIndexedTree = {
      val total = addUpTo(fullDrops) + restToDrop + n
      val bound = findLowerBoundIndexByNumberOfElements(n)
      val sum = addUpTo(bound + 1)
      val newFullDrops = if (sum == total) bound + 1 else bound
      var newRestToDrop = if (sum == total) 0 else total - addUpTo(bound)

      if (newFullDrops == fullDrops)
        newRestToDrop = math.max(newRestToDrop, restToDrop)

      BinaryIndexedTree(newFullDrops, newRestToDrop, fullTakes, restToTake, sumTable, elemTable)
    }

    def slice(from: Int, until: Int): BinaryIndexedTree = {
      val length = total
      if (from <= 0 && length <= until) this
      else if (until <= from) BinaryIndexedTree.empty
      else if (0 <= from && until <= length) slice1(from, until)
      else if (from < 0) take(until)
      else drop(from)
    }

    private[this] def slice1(from: Int, until: Int): BinaryIndexedTree = {
      val fromTotal = addUpTo(fullDrops) + restToDrop + from
      val fromBound = findLowerBoundIndexByNumberOfElements(from + 1)
      val fromSum = addUpTo(fromBound + 1)
      val newFullDrops = if (fromTotal == fromSum) fromBound + 1 else fromBound
      var newRestToDrop = if (fromTotal == fromSum) 0 else fromTotal - addUpTo(fromBound)

      val untilTotal = addUpTo(fullDrops) + restToDrop + until
      val untilBound = findLowerBoundIndexByNumberOfElements(until)
      val untilSum = addUpTo(untilBound + 1)
      val newFullTakes = if (untilTotal == untilSum) untilBound + 1 else untilBound
      var newRestToTake = if (untilTotal == untilSum) 0 else untilTotal - addUpTo(untilBound)

      if (newFullDrops == fullDrops) newRestToDrop = math.max(newRestToDrop, restToDrop)
      if (newFullTakes == fullTakes) newRestToTake = math.min(newRestToTake, restToTake)

      BinaryIndexedTree(newFullDrops, newRestToDrop, newFullTakes, newRestToTake, sumTable, elemTable)
    }

    /**
     * Number of valid BIT elements based on fullDrops, restToDrop, fullTakes and restToTake.
     */
    def size: Int = {
      if (isEmpty) 0
      else if (fullTakes == fullDrops) 1
      else {
        val base = fullTakes - fullDrops
        if (restToTake > 0) base + 1
        else base
      }
    }

    /**
     * Return index (fullTakes, restToTake) to get i-th element
     * considering fullDrops and restToDrop.
     * @param n (1-based) i-th element to calculate the index where 0 < n < length is always satisfied.
     */
    def getIndex(n: Int): (Int, Int) = {
      // FixMe: Just calculate fullTakes and restToTake instead of creating a new instance (bit),
      //        which should be faster as we don't generate an instance.
      val newBIT = take(n)
      val fullTakes = newBIT.fullTakes - newBIT.fullDrops
      val restToTake = if (fullTakes == 0) newBIT.restToTake - newBIT.restToDrop else newBIT.restToTake
      (fullTakes, restToTake)
    }

    @inline
    private[this] def adjustElemTable(firstIdx: Int, lastIdx: Int, restToDrop: Int, restToTake: Int, elemTable: Array[Int]): Unit = {
      // To correctly handle this (especially where fullDrops == fullTakes is met),
      // we need to do the following *in order*:
      // 1. Substitute restToTake to the last elem of both this and other if necessary (restToTake > 0)
      // 2. Subtract restToDrop from the first elem of both this and other
      if (restToTake > 0) elemTable(lastIdx) = restToTake
      /*if (restToDrop > 0)*/ elemTable(firstIdx) -= restToDrop
    }

    def :+(other: Int): BinaryIndexedTree = {
      if (other <= 0) this
      else {
        if (this.isEmpty) {
          BinaryIndexedTree(0, 0, 1, 0, Array(other), Array(other))
        } else {
          val thisSize = this.size
          val newTableSize = thisSize + 1
          val newElemTable = new Array[Int](newTableSize)
          newElemTable(newTableSize - 1) = other
          System.arraycopy(this.elemTable, this.fullDrops, newElemTable, 0, thisSize)
          adjustElemTable(0, newTableSize - 2, restToDrop, restToTake, newElemTable)

          BinaryIndexedTree(0, 0, newTableSize, 0, BinaryIndexedTree.createSumTable(newElemTable), newElemTable)
        }
      }
    }

    def +:(other: Int): BinaryIndexedTree = {
      if (other <= 0) this
      else {
        if (this.isEmpty) {
          BinaryIndexedTree(0, 0, 1, 0, Array(other), Array(other))
        } else {
          val thisSize = this.size
          val newTableSize = thisSize + 1
          val newElemTable = new Array[Int](newTableSize)
          newElemTable(0) = other
          System.arraycopy(this.elemTable, this.fullDrops, newElemTable, 1, thisSize)
          adjustElemTable(1, newTableSize - 1, restToDrop, restToTake, newElemTable)

          BinaryIndexedTree(0, 0, newTableSize, 0, BinaryIndexedTree.createSumTable(newElemTable), newElemTable)
        }
      }
    }

    def ++(other: BinaryIndexedTree): BinaryIndexedTree = {
      if (this.isEmpty) other
      else if (other.isEmpty) this
      else append1(other)
    }

    private[this] def append1(other: BinaryIndexedTree): BinaryIndexedTree = {
      val thisSize = this.size
      val otherSize = other.size
      val newTableSize = thisSize + otherSize
      val newElemTable = new Array[Int](newTableSize)
      System.arraycopy(this.elemTable, this.fullDrops, newElemTable, 0, thisSize)
      System.arraycopy(other.elemTable, other.fullDrops, newElemTable, thisSize, otherSize)

      adjustElemTable(0, thisSize - 1, this.restToDrop, this.restToTake, newElemTable)
      adjustElemTable(thisSize, newTableSize - 1, other.restToDrop, other.restToTake, newElemTable)

      val newSumTable = BinaryIndexedTree.createSumTable(newElemTable)
      BinaryIndexedTree(0, 0, newTableSize, 0, newSumTable, newElemTable)
    }
  }

  /**
   * A compact (unsliced) and unfragmented ByteString, implementation of ByteString1C.
   */
  @SerialVersionUID(3956956327691936932L)
  final class ByteString1C private (private val bytes: Array[Byte]) extends CompactByteString {
    def apply(idx: Int): Byte = bytes(idx)

    override def length: Int = bytes.length

    // Avoid `iterator` in performance sensitive code, call ops directly on ByteString instead
    override def iterator: ByteIterator.ByteArrayIterator = ByteIterator.ByteArrayIterator(bytes, 0, bytes.length)

    /** INTERNAL API */
    private[akka] def toByteString1: ByteString1 = ByteString1(bytes, 0, bytes.length)

    /** INTERNAL API */
    private[akka] def byteStringCompanion = ByteString1C

    override def asByteBuffer: ByteBuffer = toByteString1.asByteBuffer

    override def asByteBuffers: scala.collection.immutable.Iterable[ByteBuffer] = List(asByteBuffer)

    override def decodeString(charset: String): String =
      if (isEmpty) "" else new String(bytes, charset)

    override def decodeString(charset: Charset): String =
      if (isEmpty) "" else new String(bytes, charset)

    override def ++(that: ByteString): ByteString = {
      if (that.isEmpty) this
      else if (this.isEmpty) that
      else toByteString1 ++ that
    }

    override def take(n: Int): ByteString =
      if (n <= 0) ByteString.empty
      else toByteString1.take(n)

    override def dropRight(n: Int): ByteString =
      if (n <= 0) this
      else toByteString1.dropRight(n)

    override def drop(n: Int): ByteString =
      if (n <= 0) this
      else toByteString1.drop(n)

    override def indexOf[B >: Byte](elem: B): Int = indexOf(elem, 0)
    override def indexOf[B >: Byte](elem: B, from: Int): Int = {
      if (from >= length) -1
      else {
        var found = -1
        var i = math.max(from, 0)
        while (i < length && found == -1) {
          if (bytes(i) == elem) found = i
          i += 1
        }
        found
      }
    }

    override def slice(from: Int, until: Int): ByteString =
      if (from <= 0 && until >= length) this
      else if (from >= length || until <= 0 || from >= until) ByteString.empty
      else toByteString1.slice(from, until)

    private[akka] override def writeToOutputStream(os: ObjectOutputStream): Unit =
      toByteString1.writeToOutputStream(os)

    override def copyToBuffer(buffer: ByteBuffer): Int =
      writeToBuffer(buffer, offset = 0)

    /** INTERNAL API: Specialized for internal use, writing multiple ByteString1C into the same ByteBuffer. */
    private[akka] def writeToBuffer(buffer: ByteBuffer, offset: Int): Int = {
      val copyLength = Math.min(buffer.remaining, offset + length)
      if (copyLength > 0) {
        buffer.put(bytes, offset, copyLength)
      }
      copyLength
    }

    /** INTERNAL API: Specialized for internal use, appending ByteString1C to a ByteStringBuilder. */
    private[akka] def appendToBuilder(buffer: ByteStringBuilder) = {
      buffer.putByteArrayUnsafe(bytes)
    }

  }

  /** INTERNAL API: ByteString backed by exactly one array, with start / end markers */
  private[akka] object ByteString1 extends Companion {
    val empty: ByteString1 = new ByteString1(Array.empty[Byte])
    def fromString(s: String): ByteString1 = apply(s.getBytes)
    def apply(bytes: Array[Byte]): ByteString1 = apply(bytes, 0, bytes.length)
    def apply(bytes: Array[Byte], startIndex: Int, length: Int): ByteString1 =
      if (length == 0) empty
      else new ByteString1(bytes, Math.max(0, startIndex), Math.max(0, length))

    val SerializationIdentity = 0.toByte

    def readFromInputStream(is: ObjectInputStream): ByteString1 =
      ByteString1C.readFromInputStream(is).toByteString1
  }

  /**
   * An unfragmented ByteString.
   */
  final class ByteString1 private (private val bytes: Array[Byte], private val startIndex: Int, val length: Int) extends ByteString with Serializable {

    private def this(bytes: Array[Byte]) = this(bytes, 0, bytes.length)

    def apply(idx: Int): Byte = bytes(checkRangeConvert(idx))

    // Avoid `iterator` in performance sensitive code, call ops directly on ByteString instead
    override def iterator: ByteIterator.ByteArrayIterator =
      ByteIterator.ByteArrayIterator(bytes, startIndex, startIndex + length)

    private def checkRangeConvert(index: Int): Int = {
      if (0 <= index && length > index)
        index + startIndex
      else
        throw new IndexOutOfBoundsException(index.toString)
    }

    private[akka] def writeToOutputStream(os: ObjectOutputStream): Unit = {
      os.writeInt(length)
      os.write(bytes, startIndex, length)
    }

    def isCompact: Boolean = (length == bytes.length)

    private[akka] def byteStringCompanion = ByteString1

    override def dropRight(n: Int): ByteString =
      dropRight1(n)

    /** INTERNAL API */
    private[akka] def dropRight1(n: Int): ByteString1 =
      if (n <= 0) this
      else if (length - n <= 0) ByteString1.empty
      else new ByteString1(bytes, startIndex, length - n)

    override def drop(n: Int): ByteString =
      if (n <= 0) this else drop1(n)

    /** INTERNAL API */
    private[akka] def drop1(n: Int): ByteString1 = {
      val nextStartIndex = startIndex + n
      if (nextStartIndex >= bytes.length) ByteString1.empty
      else ByteString1(bytes, nextStartIndex, length - n)
    }

    override def take(n: Int): ByteString =
      if (n <= 0) ByteString.empty else take1(n)

    private[akka] def take1(n: Int): ByteString1 =
      if (n >= length) this
      else ByteString1(bytes, startIndex, n)

    override def slice(from: Int, until: Int): ByteString =
      if (from <= 0 && length <= until) this
      else slice1(from, until)

    private[akka] def slice1(from: Int, until: Int): ByteString1 =
      // if (from <= 0 && length <= until) this
      if (until <= from) ByteString1.empty
      else if (0 <= from && until <= bytes.length) ByteString1(bytes, startIndex + from, until - from)
      else if (from < 0) ByteString1(bytes, startIndex, until)
      else /*(until > bytes.length)*/ ByteString1(bytes, startIndex + from, bytes.length - from)

    override def copyToBuffer(buffer: ByteBuffer): Int =
      writeToBuffer(buffer)

    /** INTERNAL API: Specialized for internal use, writing multiple ByteString1C into the same ByteBuffer. */
    private[akka] def writeToBuffer(buffer: ByteBuffer): Int = {
      val copyLength = Math.min(buffer.remaining, length)
      if (copyLength > 0) {
        buffer.put(bytes, startIndex, copyLength)
        drop(copyLength)
      }
      copyLength
    }

    def compact: CompactByteString =
      if (isCompact) ByteString1C(bytes) else ByteString1C(toArray)

    def asByteBuffer: ByteBuffer = {
      val buffer = ByteBuffer.wrap(bytes, startIndex, length).asReadOnlyBuffer
      if (buffer.remaining < bytes.length) buffer.slice
      else buffer
    }

    def asByteBuffers: scala.collection.immutable.Iterable[ByteBuffer] = List(asByteBuffer)

    override def decodeString(charset: String): String =
      new String(if (length == bytes.length) bytes else toArray, charset)

    override def decodeString(charset: Charset): String = // avoids Charset.forName lookup in String internals
      new String(if (length == bytes.length) bytes else toArray, charset)

    def ++(that: ByteString): ByteString = {
      if (that.isEmpty) this
      else if (this.isEmpty) that
      else that match {
        case b: ByteString1C ⇒ ByteStrings(this, b.toByteString1)
        case b: ByteString1 ⇒
          if ((bytes eq b.bytes) && (startIndex + length == b.startIndex))
            new ByteString1(bytes, startIndex, length + b.length)
          else ByteStrings(this, b)
        case bs: ByteStrings ⇒ ByteStrings(this, bs)
      }
    }

    override def indexOf[B >: Byte](elem: B): Int = indexOf(elem, 0)
    override def indexOf[B >: Byte](elem: B, from: Int): Int = {
      if (from >= length) -1
      else {
        var found = -1
        var i = math.max(from, 0)
        while (i < length && found == -1) {
          if (bytes(startIndex + i) == elem) found = i
          i += 1
        }
        found
      }
    }

    protected def writeReplace(): AnyRef = new SerializationProxy(this)
  }

  private[akka] object ByteStrings extends Companion {
    def apply(bytestrings: Vector[ByteString1]): ByteString = new ByteStrings(bytestrings, (0 /: bytestrings)(_ + _.length), BinaryIndexedTree(bytestrings))

    def apply(bytestrings: Vector[ByteString1], length: Int): ByteString = new ByteStrings(bytestrings, length, BinaryIndexedTree(bytestrings))

    def apply(b1: ByteString1, b2: ByteString1): ByteString = compare(b1, b2) match {
      case 3 ⇒
        val byteStrings = Vector(b1, b2)
        new ByteStrings(byteStrings, b1.length + b2.length, BinaryIndexedTree(byteStrings))
      case 2 ⇒ b2
      case 1 ⇒ b1
      case 0 ⇒ ByteString.empty
    }

    def apply(b: ByteString1, bs: ByteStrings): ByteString = compare(b, bs) match {
      case 3 ⇒
        val byteStrings = b +: bs.bytestrings
        new ByteStrings(byteStrings, bs.length + b.length, b.length +: bs.bit)
      case 2 ⇒ bs
      case 1 ⇒ b
      case 0 ⇒ ByteString.empty
    }

    def apply(bs: ByteStrings, b: ByteString1): ByteString = compare(bs, b) match {
      case 3 ⇒
        val byteStrings = bs.bytestrings :+ b
        new ByteStrings(byteStrings, bs.length + b.length, bs.bit :+ b.length)
      case 2 ⇒ b
      case 1 ⇒ bs
      case 0 ⇒ ByteString.empty
    }

    def apply(bs1: ByteStrings, bs2: ByteStrings): ByteString = compare(bs1, bs2) match {
      case 3 ⇒
        new ByteStrings(bs1.bytestrings ++ bs2.bytestrings, bs1.length + bs2.length, bs1.bit ++ bs2.bit)
      case 2 ⇒ bs2
      case 1 ⇒ bs1
      case 0 ⇒ ByteString.empty
    }

    // 0: both empty, 1: 2nd empty, 2: 1st empty, 3: neither empty
    def compare(b1: ByteString, b2: ByteString): Int =
      if (b1.isEmpty)
        if (b2.isEmpty) 0 else 2
      else if (b2.isEmpty) 1 else 3

    val SerializationIdentity = 2.toByte

    def readFromInputStream(is: ObjectInputStream): ByteStrings = {
      val nByteStrings = is.readInt()

      val builder = new VectorBuilder[ByteString1]
      val elemTable = new Array[Int](nByteStrings)
      var length = 0

      builder.sizeHint(nByteStrings)

      for (i ← 0 until nByteStrings) {
        val bs = ByteString1.readFromInputStream(is)
        builder += bs
        length += bs.length
        elemTable(i) = bs.length
      }

      val byteStrings = builder.result()
      val bit = BinaryIndexedTree(0, 0, nByteStrings, 0, BinaryIndexedTree.createSumTable(elemTable), elemTable)
      new ByteStrings(byteStrings, length, bit)
    }
  }

  /**
   * A ByteString with 2 or more fragments.
   */
  final class ByteStrings private (private[akka] val bytestrings: Vector[ByteString1], val length: Int, private[akka] val bit: BinaryIndexedTree) extends ByteString with Serializable {
    if (bytestrings.isEmpty) throw new IllegalArgumentException("bytestrings must not be empty")
    if (bytestrings.head.isEmpty) throw new IllegalArgumentException("bytestrings.head must not be empty")

    def apply(idx: Int): Byte = {
      if (0 <= idx && idx < length) {
        val (pos, i) = bit.getIndex(idx)
        bytestrings(pos)(i)
      } else throw new IndexOutOfBoundsException(idx.toString)
    }

    /** Avoid `iterator` in performance sensitive code, call ops directly on ByteString instead */
    override def iterator: ByteIterator.MultiByteArrayIterator =
      ByteIterator.MultiByteArrayIterator(bytestrings.toStream map { _.iterator })

    def ++(that: ByteString): ByteString = {
      if (that.isEmpty) this
      else if (this.isEmpty) that
      else that match {
        case b: ByteString1C ⇒ ByteStrings(this, b.toByteString1)
        case b: ByteString1  ⇒ ByteStrings(this, b)
        case bs: ByteStrings ⇒ ByteStrings(this, bs)
      }
    }

    private[akka] def byteStringCompanion = ByteStrings

    def isCompact: Boolean = if (bytestrings.length == 1) bytestrings.head.isCompact else false

    override def copyToBuffer(buffer: ByteBuffer): Int = {
      @tailrec def copyItToTheBuffer(buffer: ByteBuffer, i: Int, written: Int): Int =
        if (i < bytestrings.length) copyItToTheBuffer(buffer, i + 1, written + bytestrings(i).writeToBuffer(buffer))
        else written

      copyItToTheBuffer(buffer, 0, 0)
    }

    def compact: CompactByteString = {
      if (isCompact) bytestrings.head.compact
      else {
        val ar = new Array[Byte](length)
        var pos = 0
        bytestrings foreach { b ⇒
          b.copyToArray(ar, pos, b.length)
          pos += b.length
        }
        ByteString1C(ar)
      }
    }

    def asByteBuffer: ByteBuffer = compact.asByteBuffer

    def asByteBuffers: scala.collection.immutable.Iterable[ByteBuffer] = bytestrings map { _.asByteBuffer }

    def decodeString(charset: String): String = compact.decodeString(charset)

    def decodeString(charset: Charset): String =
      compact.decodeString(charset)

    private[akka] def writeToOutputStream(os: ObjectOutputStream): Unit = {
      os.writeInt(bytestrings.length)
      bytestrings.foreach(_.writeToOutputStream(os))
    }

    override def take(n: Int): ByteString =
      if (0 < n && n < length) take0(n)
      else if (n <= 0) ByteString.empty
      else this

    private[this] def take0(n: Int): ByteString = {
      val newBIT = bit.take(n)
      val fullTakes = newBIT.fullTakes - newBIT.fullDrops
      val restToTake = if (fullTakes == 0) newBIT.restToTake - newBIT.restToDrop else newBIT.restToTake
      val vectorSize = bytestrings.size

      if (fullTakes == 0)
        bytestrings(fullTakes).take(restToTake)
      else if (fullTakes * 2 < vectorSize) {
        if (restToTake == 0)
          new ByteStrings(bytestrings.take(fullTakes), n, newBIT)
        else
          new ByteStrings(bytestrings.take(fullTakes) :+ bytestrings(fullTakes).take1(restToTake), n, newBIT)
      } else {
        if (restToTake == 0)
          new ByteStrings(bytestrings.dropRight(vectorSize - fullTakes), n, newBIT)
        else
          new ByteStrings(bytestrings.dropRight(vectorSize - fullTakes) :+ bytestrings(fullTakes).take1(restToTake), n, newBIT)
      }
    }

    override def dropRight(n: Int): ByteString =
      if (0 < n && n < length) take0(length - n)
      else if (n >= length) ByteString.empty
      else this

    override def slice(from: Int, until: Int): ByteString =
      if (from <= 0 && length <= until) this
      else if (until <= from) ByteString.empty
      else if (0 <= from && until <= length) slice1(from, until) // drop0(from).take(until - from) // TODO: optimization
      else if (from < 0) take(until)
      else drop(from)

    private[this] def slice1(from: Int, until: Int): ByteString = {
      val newBIT = bit.slice(from, until)
      val fullDrops = newBIT.fullDrops - bit.fullDrops
      val restToDrop = if (fullDrops == 0) newBIT.restToDrop - bit.restToDrop else newBIT.restToDrop

      if (newBIT.fullDrops == newBIT.fullTakes) {
        //FixMe: is it faster if we do the following as we don't have to create a new BIT again?
        // new ByteStrings(Vector(bss), until - from, newBIT)
        bytestrings(fullDrops).slice(restToDrop, newBIT.restToTake)
      } else if (newBIT.fullTakes - newBIT.fullDrops == 1) {
        if (restToDrop == 0) {
          if (newBIT.restToTake == 0)
            //FixMe: is it faster if we do the following?
            // new ByteStrings(Vector(bss), until - from, newBIT)
            bytestrings(fullDrops)
          else
            new ByteStrings(Vector(
              bytestrings(fullDrops),
              bytestrings(fullDrops + 1).take1(newBIT.restToTake)), until - from, newBIT)
        } else {
          if (newBIT.restToTake == 0)
            //FixMe: is it faster if we do the following?
            // new ByteStrings(Vector(bss), until - from, newBIT)
            bytestrings(fullDrops).drop1(restToDrop)
          else
            new ByteStrings(Vector(
              bytestrings(fullDrops).drop1(restToDrop),
              bytestrings(fullDrops + 1).take1(newBIT.restToTake)), until - from, newBIT)
        }
      } else {
        val fullTakes = newBIT.fullTakes - newBIT.fullDrops
        val base = bytestrings.slice(if (restToDrop > 0) fullDrops + 1 else fullDrops, fullDrops + fullTakes)
        val bss =
          if (restToDrop == 0 && newBIT.restToTake == 0)
            base
          else if (newBIT.restToTake == 0)
            bytestrings(fullDrops).drop1(restToDrop) +: base
          else if (restToDrop == 0)
            base :+ bytestrings(fullDrops + fullTakes).take1(newBIT.restToTake)
          else
            bytestrings(fullDrops).drop1(restToDrop) +: base :+ bytestrings(fullDrops + fullTakes).take1(newBIT.restToTake)
        new ByteStrings(bss, until - from, newBIT)
      }
    }

    override def drop(n: Int): ByteString =
      if (0 < n && n < length) drop0(n)
      else if (n >= length) ByteString.empty
      else this

    private[this] def drop0(n: Int): ByteString = {
      // impl note: could be optimised a bit by using VectorIterator instead,
      //            however then we're forced to call .toVector which halfs performance
      //            We can work around that, as there's a Scala private method "remainingVector" which is fast,
      //            but let's not go into calling private APIs here just yet.
      val newBIT = bit.drop(n)
      val fullDrops = newBIT.fullDrops - bit.fullDrops
      val restToDrop = if (fullDrops == 0) newBIT.restToDrop - bit.restToDrop else newBIT.restToDrop
      val vectorSize = bytestrings.size

      if (fullDrops == vectorSize - 1)
        bytestrings(fullDrops).drop(restToDrop)
      else if (fullDrops * 2 < vectorSize) {
        if (restToDrop == 0)
          new ByteStrings(bytestrings.drop(fullDrops), length - n, newBIT)
        else
          new ByteStrings(bytestrings(fullDrops).drop1(restToDrop) +: bytestrings.drop(fullDrops + 1), length - n, newBIT)
      } else {
        if (restToDrop == 0)
          new ByteStrings(bytestrings.takeRight(vectorSize - fullDrops), length - n, newBIT)
        else
          new ByteStrings(bytestrings(fullDrops).drop1(restToDrop) +: bytestrings.takeRight(vectorSize - fullDrops - 1), length - n, newBIT)
      }
    }

    override def indexOf[B >: Byte](elem: B): Int = indexOf(elem, 0)
    override def indexOf[B >: Byte](elem: B, from: Int): Int = {
      if (from >= length) -1
      else {
        val byteStringsSize = bytestrings.size

        @tailrec
        def find(bsIdx: Int, relativeIndex: Int, bytesPassed: Int): Int = {
          if (bsIdx >= byteStringsSize) -1
          else {
            val bs = bytestrings(bsIdx)

            if (bs.length <= relativeIndex) {
              find(bsIdx + 1, relativeIndex - bs.length, bytesPassed + bs.length)
            } else {
              val subIndexOf = bs.indexOf(elem, relativeIndex)
              if (subIndexOf < 0) {
                val nextString = bsIdx + 1
                find(nextString, relativeIndex - bs.length, bytesPassed + bs.length)
              } else subIndexOf + bytesPassed
            }
          }
        }

        find(0, math.max(from, 0), 0)
      }
    }

    protected def writeReplace(): AnyRef = new SerializationProxy(this)
  }

  @SerialVersionUID(1L)
  private class SerializationProxy(@transient private var orig: ByteString) extends Serializable {
    private def writeObject(out: ObjectOutputStream) {
      out.writeByte(orig.byteStringCompanion.SerializationIdentity)
      orig.writeToOutputStream(out)
    }

    private def readObject(in: ObjectInputStream) {
      val serializationId = in.readByte()

      orig = Companion(from = serializationId).readFromInputStream(in)
    }

    private def readResolve(): AnyRef = orig
  }

  private[akka] object Companion {
    private val companionMap = Seq(ByteString1, ByteString1C, ByteStrings).
      map(x ⇒ x.SerializationIdentity → x).toMap.
      withDefault(x ⇒ throw new IllegalArgumentException("Invalid serialization id " + x))

    def apply(from: Byte): Companion = companionMap(from)
  }

  private[akka] sealed trait Companion {
    def SerializationIdentity: Byte
    def readFromInputStream(is: ObjectInputStream): ByteString
  }
}

/**
 * A rope-like immutable data structure containing bytes.
 * The goal of this structure is to reduce copying of arrays
 * when concatenating and slicing sequences of bytes,
 * and also providing a thread safe way of working with bytes.
 *
 * TODO: Add performance characteristics
 */
sealed abstract class ByteString extends IndexedSeq[Byte] with IndexedSeqOptimized[Byte, ByteString] {
  def apply(idx: Int): Byte
  private[akka] def byteStringCompanion: ByteString.Companion
  // override so that toString will also be `ByteString(...)` for the concrete subclasses
  // of ByteString which changed for Scala 2.12, see https://github.com/akka/akka/issues/21774
  override final def stringPrefix: String = "ByteString"

  override protected[this] def newBuilder: ByteStringBuilder = ByteString.newBuilder

  // *must* be overridden by derived classes. This construction is necessary
  // to specialize the return type, as the method is already implemented in
  // a parent trait.
  // 
  // Avoid `iterator` in performance sensitive code, call ops directly on ByteString instead
  override def iterator: ByteIterator = throw new UnsupportedOperationException("Method iterator is not implemented in ByteString")

  override def head: Byte = apply(0)
  override def tail: ByteString = drop(1)
  override def last: Byte = apply(length - 1)
  override def init: ByteString = dropRight(1)

  // *must* be overridden by derived classes.
  override def take(n: Int): ByteString = throw new UnsupportedOperationException("Method take is not implemented in ByteString")
  override def takeRight(n: Int): ByteString = slice(length - n, length)

  // these methods are optimized in derived classes utilising the maximum knowlage about data layout available to them:
  // *must* be overridden by derived classes.
  override def slice(from: Int, until: Int): ByteString = throw new UnsupportedOperationException("Method slice is not implemented in ByteString")

  // *must* be overridden by derived classes.
  override def drop(n: Int): ByteString = throw new UnsupportedOperationException("Method drop is not implemented in ByteString")

  // *must* be overridden by derived classes.
  override def dropRight(n: Int): ByteString = throw new UnsupportedOperationException("Method dropRight is not implemented in ByteString")

  override def takeWhile(p: Byte ⇒ Boolean): ByteString = iterator.takeWhile(p).toByteString
  override def dropWhile(p: Byte ⇒ Boolean): ByteString = iterator.dropWhile(p).toByteString
  override def span(p: Byte ⇒ Boolean): (ByteString, ByteString) =
    { val (a, b) = iterator.span(p); (a.toByteString, b.toByteString) }

  override def splitAt(n: Int): (ByteString, ByteString) = (take(n), drop(n))

  override def indexWhere(p: Byte ⇒ Boolean): Int = iterator.indexWhere(p)

  // optimized in subclasses
  override def indexOf[B >: Byte](elem: B): Int = indexOf(elem, 0)

  override def toString(): String = {
    val maxSize = 100
    if (size > maxSize)
      take(maxSize).toString + s"... and [${size - maxSize}] more"
    else
      super.toString
  }

  /**
   * Java API: copy this ByteString into a fresh byte array
   *
   * @return this ByteString copied into a byte array
   */
  protected[ByteString] def toArray: Array[Byte] = toArray[Byte]

  override def toArray[B >: Byte](implicit arg0: ClassTag[B]): Array[B] = iterator.toArray
  override def copyToArray[B >: Byte](xs: Array[B], start: Int, len: Int): Unit =
    iterator.copyToArray(xs, start, len)

  override def foreach[@specialized U](f: Byte ⇒ U): Unit = iterator foreach f

  private[akka] def writeToOutputStream(os: ObjectOutputStream): Unit

  /**
   * Efficiently concatenate another ByteString.
   */
  def ++(that: ByteString): ByteString

  /**
   * Java API: efficiently concatenate another ByteString.
   */
  def concat(that: ByteString): ByteString = this ++ that

  /**
   * Copy as many bytes as possible to a ByteBuffer, starting from it's
   * current position. This method will not overflow the buffer.
   *
   * @param buffer a ByteBuffer to copy bytes to
   * @return the number of bytes actually copied
   */
  // *must* be overridden by derived classes. 
  def copyToBuffer(buffer: ByteBuffer): Int = throw new UnsupportedOperationException("Method copyToBuffer is not implemented in ByteString")

  /**
   * Create a new ByteString with all contents compacted into a single,
   * full byte array.
   * If isCompact returns true, compact is an O(1) operation, but
   * might return a different object with an optimized implementation.
   */
  def compact: CompactByteString

  /**
   * Check whether this ByteString is compact in memory.
   * If the ByteString is compact, it might, however, not be represented
   * by an object that takes full advantage of that fact. Use compact to
   * get such an object.
   */
  def isCompact: Boolean

  /**
   * Returns a read-only ByteBuffer that directly wraps this ByteString
   * if it is not fragmented.
   */
  def asByteBuffer: ByteBuffer

  /**
   * Scala API: Returns an immutable Iterable of read-only ByteBuffers that directly wraps this ByteStrings
   * all fragments. Will always have at least one entry.
   */
  def asByteBuffers: immutable.Iterable[ByteBuffer]

  /**
   * Java API: Returns an Iterable of read-only ByteBuffers that directly wraps this ByteStrings
   * all fragments. Will always have at least one entry.
   */
  def getByteBuffers(): JIterable[ByteBuffer] = {
    import scala.collection.JavaConverters.asJavaIterableConverter
    asByteBuffers.asJava
  }

  /**
   * Creates a new ByteBuffer with a copy of all bytes contained in this
   * ByteString.
   */
  def toByteBuffer: ByteBuffer = ByteBuffer.wrap(toArray)

  /**
   * Decodes this ByteString as a UTF-8 encoded String.
   */
  final def utf8String: String = decodeString(ByteString.UTF_8)

  /**
   * Decodes this ByteString using a charset to produce a String.
   * If you have a [[Charset]] instance available, use `decodeString(charset: java.nio.charset.Charset` instead.
   */
  def decodeString(charset: String): String

  /**
   * Decodes this ByteString using a charset to produce a String.
   * Avoids Charset.forName lookup in String internals, thus is preferable to `decodeString(charset: String)`.
   */
  def decodeString(charset: Charset): String

  /**
   * map method that will automatically cast Int back into Byte.
   */
  final def mapI(f: Byte ⇒ Int): ByteString = map(f andThen (_.toByte))
}

object CompactByteString {
  /**
   * Creates a new CompactByteString by copying a byte array.
   */
  def apply(bytes: Array[Byte]): CompactByteString =
    if (bytes.isEmpty) empty else ByteString.ByteString1C(bytes.clone)

  /**
   * Creates a new CompactByteString by copying bytes.
   */
  def apply(bytes: Byte*): CompactByteString = {
    if (bytes.isEmpty) empty
    else {
      val ar = new Array[Byte](bytes.size)
      bytes.copyToArray(ar)
      ByteString.ByteString1C(ar)
    }
  }

  /**
   * Creates a new CompactByteString by converting from integral numbers to bytes.
   */
  def apply[T](bytes: T*)(implicit num: Integral[T]): CompactByteString = {
    if (bytes.isEmpty) empty
    else ByteString.ByteString1C(bytes.map(x ⇒ num.toInt(x).toByte)(collection.breakOut))
  }

  /**
   * Creates a new CompactByteString by copying bytes from a ByteBuffer.
   */
  def apply(bytes: ByteBuffer): CompactByteString = {
    if (bytes.remaining < 1) empty
    else {
      val ar = new Array[Byte](bytes.remaining)
      bytes.get(ar)
      ByteString.ByteString1C(ar)
    }
  }

  /**
   * Creates a new CompactByteString by encoding a String as UTF-8.
   */
  def apply(string: String): CompactByteString = apply(string, ByteString.UTF_8)

  /**
   * Creates a new CompactByteString by encoding a String with a charset.
   */
  def apply(string: String, charset: String): CompactByteString =
    if (string.isEmpty) empty else ByteString.ByteString1C(string.getBytes(charset))

  /**
   * Creates a new CompactByteString by copying length bytes starting at offset from
   * an Array.
   */
  def fromArray(array: Array[Byte], offset: Int, length: Int): CompactByteString = {
    val copyOffset = Math.max(offset, 0)
    val copyLength = Math.max(Math.min(array.length - copyOffset, length), 0)
    if (copyLength == 0) empty
    else {
      val copyArray = new Array[Byte](copyLength)
      Array.copy(array, copyOffset, copyArray, 0, copyLength)
      ByteString.ByteString1C(copyArray)
    }
  }

  val empty: CompactByteString = ByteString.ByteString1C(Array.empty[Byte])
}

/**
 * A compact ByteString.
 *
 * The ByteString is guarantied to be contiguous in memory and to use only
 * as much memory as required for its contents.
 */
sealed abstract class CompactByteString extends ByteString with Serializable {
  def isCompact: Boolean = true
  def compact: this.type = this
}

/**
 * A mutable builder for efficiently creating a [[akka.util.ByteString]].
 *
 * The created ByteString is not automatically compacted.
 */
final class ByteStringBuilder extends Builder[Byte, ByteString] {
  builder ⇒

  import ByteString.{ ByteString1C, ByteString1, ByteStrings }
  private var _length: Int = 0
  private val _builder: VectorBuilder[ByteString1] = new VectorBuilder[ByteString1]()
  private var _temp: Array[Byte] = _
  private var _tempLength: Int = 0
  private var _tempCapacity: Int = 0

  protected def fillArray(len: Int)(fill: (Array[Byte], Int) ⇒ Unit): this.type = {
    ensureTempSize(_tempLength + len)
    fill(_temp, _tempLength)
    _tempLength += len
    _length += len
    this
  }

  @inline protected final def fillByteBuffer(len: Int, byteOrder: ByteOrder)(fill: ByteBuffer ⇒ Unit): this.type = {
    fillArray(len) {
      case (array, start) ⇒
        val buffer = ByteBuffer.wrap(array, start, len)
        buffer.order(byteOrder)
        fill(buffer)
    }
  }

  def length: Int = _length

  override def sizeHint(len: Int): Unit = {
    resizeTemp(len - (_length - _tempLength))
  }

  private def clearTemp(): Unit = {
    if (_tempLength > 0) {
      val arr = new Array[Byte](_tempLength)
      Array.copy(_temp, 0, arr, 0, _tempLength)
      _builder += ByteString1(arr)
      _tempLength = 0
    }
  }

  private def resizeTemp(size: Int): Unit = {
    val newtemp = new Array[Byte](size)
    if (_tempLength > 0) Array.copy(_temp, 0, newtemp, 0, _tempLength)
    _temp = newtemp
    _tempCapacity = _temp.length
  }

  @inline private def shouldResizeTempFor(size: Int): Boolean = _tempCapacity < size || _tempCapacity == 0

  private def ensureTempSize(size: Int): Unit = {
    if (shouldResizeTempFor(size)) {
      var newSize = if (_tempCapacity == 0) 16 else _tempCapacity * 2
      while (newSize < size) newSize *= 2
      resizeTemp(newSize)
    }
  }

  def +=(elem: Byte): this.type = {
    ensureTempSize(_tempLength + 1)
    _temp(_tempLength) = elem
    _tempLength += 1
    _length += 1
    this
  }

  override def ++=(xs: TraversableOnce[Byte]): this.type = {
    xs match {
      case b: ByteString if b.isEmpty ⇒
      // do nothing
      case b: ByteString1C ⇒
        clearTemp()
        _builder += b.toByteString1
        _length += b.length
      case b: ByteString1 ⇒
        clearTemp()
        _builder += b
        _length += b.length
      case bs: ByteStrings ⇒
        clearTemp()
        _builder ++= bs.bytestrings
        _length += bs.length
      case xs: WrappedArray.ofByte ⇒
        putByteArrayUnsafe(xs.array.clone)
      case seq: collection.IndexedSeq[Byte] if shouldResizeTempFor(seq.length) ⇒
        val copied = new Array[Byte](seq.length)
        seq.copyToArray(copied)

        clearTemp()
        _builder += ByteString.ByteString1(copied)
        _length += seq.length
      case seq: collection.IndexedSeq[_] ⇒
        ensureTempSize(_tempLength + xs.size)
        xs.copyToArray(_temp, _tempLength)
        _tempLength += seq.length
        _length += seq.length
      case _ ⇒
        super.++=(xs)
    }
    this
  }

  private[akka] def putByteArrayUnsafe(xs: Array[Byte]): this.type = {
    clearTemp()
    _builder += ByteString1(xs)
    _length += xs.length
    this
  }

  /**
   * Java API: append a ByteString to this builder.
   */
  def append(bs: ByteString): this.type = if (bs.isEmpty) this else this ++= bs

  /**
   * Add a single Byte to this builder.
   */
  def putByte(x: Byte): this.type = this += x

  /**
   * Add a single Short to this builder.
   */
  def putShort(x: Int)(implicit byteOrder: ByteOrder): this.type = {
    if (byteOrder == ByteOrder.BIG_ENDIAN) {
      this += (x >>> 8).toByte
      this += (x >>> 0).toByte
    } else if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
      this += (x >>> 0).toByte
      this += (x >>> 8).toByte
    } else throw new IllegalArgumentException("Unknown byte order " + byteOrder)
  }

  /**
   * Add a single Int to this builder.
   */
  def putInt(x: Int)(implicit byteOrder: ByteOrder): this.type = {
    fillArray(4) { (target, offset) ⇒
      if (byteOrder == ByteOrder.BIG_ENDIAN) {
        target(offset + 0) = (x >>> 24).toByte
        target(offset + 1) = (x >>> 16).toByte
        target(offset + 2) = (x >>> 8).toByte
        target(offset + 3) = (x >>> 0).toByte
      } else if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
        target(offset + 0) = (x >>> 0).toByte
        target(offset + 1) = (x >>> 8).toByte
        target(offset + 2) = (x >>> 16).toByte
        target(offset + 3) = (x >>> 24).toByte
      } else throw new IllegalArgumentException("Unknown byte order " + byteOrder)
    }
    this
  }

  /**
   * Add a single Long to this builder.
   */
  def putLong(x: Long)(implicit byteOrder: ByteOrder): this.type = {
    fillArray(8) { (target, offset) ⇒
      if (byteOrder == ByteOrder.BIG_ENDIAN) {
        target(offset + 0) = (x >>> 56).toByte
        target(offset + 1) = (x >>> 48).toByte
        target(offset + 2) = (x >>> 40).toByte
        target(offset + 3) = (x >>> 32).toByte
        target(offset + 4) = (x >>> 24).toByte
        target(offset + 5) = (x >>> 16).toByte
        target(offset + 6) = (x >>> 8).toByte
        target(offset + 7) = (x >>> 0).toByte
      } else if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
        target(offset + 0) = (x >>> 0).toByte
        target(offset + 1) = (x >>> 8).toByte
        target(offset + 2) = (x >>> 16).toByte
        target(offset + 3) = (x >>> 24).toByte
        target(offset + 4) = (x >>> 32).toByte
        target(offset + 5) = (x >>> 40).toByte
        target(offset + 6) = (x >>> 48).toByte
        target(offset + 7) = (x >>> 56).toByte
      } else throw new IllegalArgumentException("Unknown byte order " + byteOrder)
    }
    this
  }

  /**
   * Add the `n` least significant bytes of the given Long to this builder.
   */
  def putLongPart(x: Long, n: Int)(implicit byteOrder: ByteOrder): this.type = {
    fillArray(n) { (target, offset) ⇒
      if (byteOrder == ByteOrder.BIG_ENDIAN) {
        val start = n * 8 - 8
        (0 until n) foreach { i ⇒ target(offset + i) = (x >>> start - 8 * i).toByte }
      } else if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
        (0 until n) foreach { i ⇒ target(offset + i) = (x >>> 8 * i).toByte }
      } else throw new IllegalArgumentException("Unknown byte order " + byteOrder)
    }
  }

  /**
   * Add a single Float to this builder.
   */
  def putFloat(x: Float)(implicit byteOrder: ByteOrder): this.type =
    putInt(java.lang.Float.floatToRawIntBits(x))(byteOrder)

  /**
   * Add a single Double to this builder.
   */
  def putDouble(x: Double)(implicit byteOrder: ByteOrder): this.type =
    putLong(java.lang.Double.doubleToRawLongBits(x))(byteOrder)

  /**
   * Add a number of Bytes from an array to this builder.
   */
  def putBytes(array: Array[Byte]): this.type =
    putBytes(array, 0, array.length)

  /**
   * Add a number of Bytes from an array to this builder.
   */
  def putBytes(array: Array[Byte], start: Int, len: Int): this.type =
    fillArray(len) { case (target, targetOffset) ⇒ Array.copy(array, start, target, targetOffset, len) }

  /**
   * Add a number of Shorts from an array to this builder.
   */
  def putShorts(array: Array[Short])(implicit byteOrder: ByteOrder): this.type =
    putShorts(array, 0, array.length)(byteOrder)

  /**
   * Add a number of Shorts from an array to this builder.
   */
  def putShorts(array: Array[Short], start: Int, len: Int)(implicit byteOrder: ByteOrder): this.type =
    fillByteBuffer(len * 2, byteOrder) { _.asShortBuffer.put(array, start, len) }

  /**
   * Add a number of Ints from an array to this builder.
   */
  def putInts(array: Array[Int])(implicit byteOrder: ByteOrder): this.type =
    putInts(array, 0, array.length)(byteOrder)

  /**
   * Add a number of Ints from an array to this builder.
   */
  def putInts(array: Array[Int], start: Int, len: Int)(implicit byteOrder: ByteOrder): this.type =
    fillByteBuffer(len * 4, byteOrder) { _.asIntBuffer.put(array, start, len) }

  /**
   * Add a number of Longs from an array to this builder.
   */
  def putLongs(array: Array[Long])(implicit byteOrder: ByteOrder): this.type =
    putLongs(array, 0, array.length)(byteOrder)

  /**
   * Add a number of Longs from an array to this builder.
   */
  def putLongs(array: Array[Long], start: Int, len: Int)(implicit byteOrder: ByteOrder): this.type =
    fillByteBuffer(len * 8, byteOrder) { _.asLongBuffer.put(array, start, len) }

  /**
   * Add a number of Floats from an array to this builder.
   */
  def putFloats(array: Array[Float])(implicit byteOrder: ByteOrder): this.type =
    putFloats(array, 0, array.length)(byteOrder)

  /**
   * Add a number of Floats from an array to this builder.
   */
  def putFloats(array: Array[Float], start: Int, len: Int)(implicit byteOrder: ByteOrder): this.type =
    fillByteBuffer(len * 4, byteOrder) { _.asFloatBuffer.put(array, start, len) }

  /**
   * Add a number of Doubles from an array to this builder.
   */
  def putDoubles(array: Array[Double])(implicit byteOrder: ByteOrder): this.type =
    putDoubles(array, 0, array.length)(byteOrder)

  /**
   * Add a number of Doubles from an array to this builder.
   */
  def putDoubles(array: Array[Double], start: Int, len: Int)(implicit byteOrder: ByteOrder): this.type =
    fillByteBuffer(len * 8, byteOrder) { _.asDoubleBuffer.put(array, start, len) }

  def clear(): Unit = {
    _builder.clear()
    _length = 0
    _tempLength = 0
  }

  def result: ByteString =
    if (_length == 0) ByteString.empty
    else {
      clearTemp()
      val bytestrings = _builder.result
      if (bytestrings.size == 1)
        bytestrings.head
      else
        ByteStrings(bytestrings, _length)
    }

  /**
   * Directly wraps this ByteStringBuilder in an OutputStream. Write
   * operations on the stream are forwarded to the builder.
   */
  def asOutputStream: java.io.OutputStream = new java.io.OutputStream {
    def write(b: Int): Unit = builder += b.toByte

    override def write(b: Array[Byte], off: Int, len: Int): Unit = { builder.putBytes(b, off, len) }
  }

  /**
   * Tests whether this ByteStringBuilder is empty.
   */
  def isEmpty: Boolean = _length == 0

  /**
   * Tests whether this ByteStringBuilder is not empty.
   */
  def nonEmpty: Boolean = _length > 0
}
