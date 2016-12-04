/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.util

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream }
import java.lang.Double.doubleToRawLongBits
import java.lang.Float.floatToRawIntBits
import java.nio.{ ByteBuffer, ByteOrder }
import java.nio.ByteOrder.{ BIG_ENDIAN, LITTLE_ENDIAN }

import akka.util.ByteString.{ ByteString1, ByteString1C, ByteStrings, BinaryIndexedTree }
import org.apache.commons.codec.binary.Hex.encodeHex
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{ Arbitrary, Gen }
import org.scalactic._
import org.scalatest.{ Matchers, WordSpec }
import org.scalatest.prop.Checkers

import scala.collection.mutable.Builder
import scala.util.Random

class ByteStringSpec extends WordSpec with Matchers with Checkers {

  implicit val betterGeneratorDrivenConfig = PropertyCheckConfig().copy(minSuccessful = 1000)

  def genSimpleByteString(min: Int, max: Int) = for {
    n ← Gen.choose(min, max)
    b ← Gen.containerOfN[Array, Byte](n, arbitrary[Byte])
    from ← Gen.choose(0, b.length)
    until ← Gen.choose(from, b.length)
  } yield ByteString(b).slice(from, until)

  implicit val arbitraryByteString: Arbitrary[ByteString] = Arbitrary {
    Gen.sized { s ⇒
      for {
        chunks ← Gen.choose(0, s)
        bytes ← Gen.listOfN(chunks, genSimpleByteString(1, s / (chunks max 1)))
      } yield (ByteString.empty /: bytes)(_ ++ _)
    }
  }

  type ByteStringSlice = (ByteString, Int, Int)

  implicit val arbitraryByteStringSlice: Arbitrary[ByteStringSlice] = Arbitrary {
    for {
      xs ← arbitraryByteString.arbitrary
      from ← Gen.choose(0, xs.length - 1)
      until ← Gen.choose(from, xs.length)
    } yield (xs, from, until)
  }

  type ArraySlice[A] = (Array[A], Int, Int)

  def arbSlice[A](arbArray: Arbitrary[Array[A]]): Arbitrary[ArraySlice[A]] = Arbitrary {
    for {
      xs ← arbArray.arbitrary
      from ← Gen.choose(0, xs.length)
      until ← Gen.choose(from, xs.length)
    } yield (xs, from, until)
  }

  def serialize(obj: AnyRef): Array[Byte] = {
    val os = new ByteArrayOutputStream
    val bos = new ObjectOutputStream(os)
    bos.writeObject(obj)
    os.toByteArray
  }

  def deserialize(bytes: Array[Byte]): AnyRef = {
    val is = new ObjectInputStream(new ByteArrayInputStream(bytes))

    is.readObject
  }

  def testSer(obj: AnyRef) = {
    deserialize(serialize(obj)) == obj
  }

  def hexFromSer(obj: AnyRef) = {
    val os = new ByteArrayOutputStream
    val bos = new ObjectOutputStream(os)
    bos.writeObject(obj)
    String valueOf encodeHex(os.toByteArray)
  }

  val arbitraryByteArray: Arbitrary[Array[Byte]] = Arbitrary { Gen.sized { n ⇒ Gen.containerOfN[Array, Byte](n, arbitrary[Byte]) } }
  implicit val arbitraryByteArraySlice: Arbitrary[ArraySlice[Byte]] = arbSlice(arbitraryByteArray)
  val arbitraryShortArray: Arbitrary[Array[Short]] = Arbitrary { Gen.sized { n ⇒ Gen.containerOfN[Array, Short](n, arbitrary[Short]) } }
  implicit val arbitraryShortArraySlice: Arbitrary[ArraySlice[Short]] = arbSlice(arbitraryShortArray)
  val arbitraryIntArray: Arbitrary[Array[Int]] = Arbitrary { Gen.sized { n ⇒ Gen.containerOfN[Array, Int](n, arbitrary[Int]) } }
  implicit val arbitraryIntArraySlice: Arbitrary[ArraySlice[Int]] = arbSlice(arbitraryIntArray)
  val arbitraryLongArray: Arbitrary[Array[Long]] = Arbitrary { Gen.sized { n ⇒ Gen.containerOfN[Array, Long](n, arbitrary[Long]) } }
  implicit val arbitraryLongArraySlice: Arbitrary[ArraySlice[Long]] = arbSlice(arbitraryLongArray)
  val arbitraryFloatArray: Arbitrary[Array[Float]] = Arbitrary { Gen.sized { n ⇒ Gen.containerOfN[Array, Float](n, arbitrary[Float]) } }
  implicit val arbitraryFloatArraySlice: Arbitrary[ArraySlice[Float]] = arbSlice(arbitraryFloatArray)
  val arbitraryDoubleArray: Arbitrary[Array[Double]] = Arbitrary { Gen.sized { n ⇒ Gen.containerOfN[Array, Double](n, arbitrary[Double]) } }
  implicit val arbitraryDoubleArraySlice: Arbitrary[ArraySlice[Double]] = arbSlice(arbitraryDoubleArray)

  type ArrayNumBytes[A] = (Array[A], Int)

  implicit val arbitraryLongArrayNumBytes: Arbitrary[ArrayNumBytes[Long]] = Arbitrary {
    for {
      xs ← arbitraryLongArray.arbitrary
      from ← Gen.choose(0, xs.length)
      until ← Gen.choose(from, xs.length)
      bytes ← Gen.choose(0, 8)
    } yield (xs.slice(from, until), bytes)
  }

  implicit val arbitraryByteStringBuilder: Arbitrary[ByteStringBuilder] = Arbitrary(ByteString.newBuilder)

  def likeVector(bs: ByteString)(body: IndexedSeq[Byte] ⇒ Any): Boolean = {
    val vec = Vector(bs: _*)
    body(bs) == body(vec)
  }

  def likeVectors(bsA: ByteString, bsB: ByteString)(body: (IndexedSeq[Byte], IndexedSeq[Byte]) ⇒ Any): Boolean = {
    val vecA = Vector(bsA: _*)
    val vecB = Vector(bsB: _*)
    body(bsA, bsB) == body(vecA, vecB)
  }

  def likeVecIt(bs: ByteString)(body: BufferedIterator[Byte] ⇒ Any, strict: Boolean = true): Boolean = {
    val bsIterator = bs.iterator
    val vecIterator = Vector(bs: _*).iterator.buffered
    (body(bsIterator) == body(vecIterator)) &&
      (!strict || (bsIterator.toSeq == vecIterator.toSeq))
  }

  def likeVecIts(a: ByteString, b: ByteString)(body: (BufferedIterator[Byte], BufferedIterator[Byte]) ⇒ Any, strict: Boolean = true): Boolean = {
    val (bsAIt, bsBIt) = (a.iterator, b.iterator)
    val (vecAIt, vecBIt) = (Vector(a: _*).iterator.buffered, Vector(b: _*).iterator.buffered)
    (body(bsAIt, bsBIt) == body(vecAIt, vecBIt)) &&
      (!strict || (bsAIt.toSeq → bsBIt.toSeq) == (vecAIt.toSeq → vecBIt.toSeq))
  }

  def likeVecBld(body: Builder[Byte, _] ⇒ Unit): Boolean = {
    val bsBuilder = ByteString.newBuilder
    val vecBuilder = Vector.newBuilder[Byte]

    body(bsBuilder)
    body(vecBuilder)

    bsBuilder.result == vecBuilder.result
  }

  def testShortDecoding(slice: ByteStringSlice, byteOrder: ByteOrder): Boolean = {
    val elemSize = 2
    val (bytes, from, until) = slice
    val (n, a, b) = (bytes.length / elemSize, from / elemSize, until / elemSize)
    val reference = Array.ofDim[Short](n)
    bytes.asByteBuffer.order(byteOrder).asShortBuffer.get(reference, 0, n)
    val input = bytes.iterator
    val decoded = Array.ofDim[Short](n)
    for (i ← 0 until a) decoded(i) = input.getShort(byteOrder)
    input.getShorts(decoded, a, b - a)(byteOrder)
    for (i ← b until n) decoded(i) = input.getShort(byteOrder)
    (decoded.toSeq == reference.toSeq) && (input.toSeq == bytes.drop(n * elemSize))
  }

  def testIntDecoding(slice: ByteStringSlice, byteOrder: ByteOrder): Boolean = {
    val elemSize = 4
    val (bytes, from, until) = slice
    val (n, a, b) = (bytes.length / elemSize, from / elemSize, until / elemSize)
    val reference = Array.ofDim[Int](n)
    bytes.asByteBuffer.order(byteOrder).asIntBuffer.get(reference, 0, n)
    val input = bytes.iterator
    val decoded = Array.ofDim[Int](n)
    for (i ← 0 until a) decoded(i) = input.getInt(byteOrder)
    input.getInts(decoded, a, b - a)(byteOrder)
    for (i ← b until n) decoded(i) = input.getInt(byteOrder)
    (decoded.toSeq == reference.toSeq) && (input.toSeq == bytes.drop(n * elemSize))
  }

  def testLongDecoding(slice: ByteStringSlice, byteOrder: ByteOrder): Boolean = {
    val elemSize = 8
    val (bytes, from, until) = slice
    val (n, a, b) = (bytes.length / elemSize, from / elemSize, until / elemSize)
    val reference = Array.ofDim[Long](n)
    bytes.asByteBuffer.order(byteOrder).asLongBuffer.get(reference, 0, n)
    val input = bytes.iterator
    val decoded = Array.ofDim[Long](n)
    for (i ← 0 until a) decoded(i) = input.getLong(byteOrder)
    input.getLongs(decoded, a, b - a)(byteOrder)
    for (i ← b until n) decoded(i) = input.getLong(byteOrder)
    (decoded.toSeq == reference.toSeq) && (input.toSeq == bytes.drop(n * elemSize))
  }

  def testFloatDecoding(slice: ByteStringSlice, byteOrder: ByteOrder): Boolean = {
    val elemSize = 4
    val (bytes, from, until) = slice
    val (n, a, b) = (bytes.length / elemSize, from / elemSize, until / elemSize)
    val reference = Array.ofDim[Float](n)
    bytes.asByteBuffer.order(byteOrder).asFloatBuffer.get(reference, 0, n)
    val input = bytes.iterator
    val decoded = Array.ofDim[Float](n)
    for (i ← 0 until a) decoded(i) = input.getFloat(byteOrder)
    input.getFloats(decoded, a, b - a)(byteOrder)
    for (i ← b until n) decoded(i) = input.getFloat(byteOrder)
    ((decoded.toSeq map floatToRawIntBits) == (reference.toSeq map floatToRawIntBits)) &&
      (input.toSeq == bytes.drop(n * elemSize))
  }

  def testDoubleDecoding(slice: ByteStringSlice, byteOrder: ByteOrder): Boolean = {
    val elemSize = 8
    val (bytes, from, until) = slice
    val (n, a, b) = (bytes.length / elemSize, from / elemSize, until / elemSize)
    val reference = Array.ofDim[Double](n)
    bytes.asByteBuffer.order(byteOrder).asDoubleBuffer.get(reference, 0, n)
    val input = bytes.iterator
    val decoded = Array.ofDim[Double](n)
    for (i ← 0 until a) decoded(i) = input.getDouble(byteOrder)
    input.getDoubles(decoded, a, b - a)(byteOrder)
    for (i ← b until n) decoded(i) = input.getDouble(byteOrder)
    ((decoded.toSeq map doubleToRawLongBits) == (reference.toSeq map doubleToRawLongBits)) &&
      (input.toSeq == bytes.drop(n * elemSize))
  }

  def testShortEncoding(slice: ArraySlice[Short], byteOrder: ByteOrder): Boolean = {
    val elemSize = 2
    val (data, from, to) = slice
    val reference = Array.ofDim[Byte](data.length * elemSize)
    ByteBuffer.wrap(reference).order(byteOrder).asShortBuffer.put(data)
    val builder = ByteString.newBuilder
    for (i ← 0 until from) builder.putShort(data(i))(byteOrder)
    builder.putShorts(data, from, to - from)(byteOrder)
    for (i ← to until data.length) builder.putShort(data(i))(byteOrder)
    reference.toSeq == builder.result
  }

  def testIntEncoding(slice: ArraySlice[Int], byteOrder: ByteOrder): Boolean = {
    val elemSize = 4
    val (data, from, to) = slice
    val reference = Array.ofDim[Byte](data.length * elemSize)
    ByteBuffer.wrap(reference).order(byteOrder).asIntBuffer.put(data)
    val builder = ByteString.newBuilder
    for (i ← 0 until from) builder.putInt(data(i))(byteOrder)
    builder.putInts(data, from, to - from)(byteOrder)
    for (i ← to until data.length) builder.putInt(data(i))(byteOrder)
    reference.toSeq == builder.result
  }

  def testLongEncoding(slice: ArraySlice[Long], byteOrder: ByteOrder): Boolean = {
    val elemSize = 8
    val (data, from, to) = slice
    val reference = Array.ofDim[Byte](data.length * elemSize)
    ByteBuffer.wrap(reference).order(byteOrder).asLongBuffer.put(data)
    val builder = ByteString.newBuilder
    for (i ← 0 until from) builder.putLong(data(i))(byteOrder)
    builder.putLongs(data, from, to - from)(byteOrder)
    for (i ← to until data.length) builder.putLong(data(i))(byteOrder)
    reference.toSeq == builder.result
  }

  def testLongPartEncoding(anb: ArrayNumBytes[Long], byteOrder: ByteOrder): Boolean = {
    val elemSize = 8
    val (data, nBytes) = anb

    val reference = Array.ofDim[Byte](data.length * elemSize)
    ByteBuffer.wrap(reference).order(byteOrder).asLongBuffer.put(data)
    val builder = ByteString.newBuilder
    for (i ← 0 until data.length) builder.putLongPart(data(i), nBytes)(byteOrder)

    reference.zipWithIndex.collect({ // Since there is no partial put on LongBuffer, we need to collect only the interesting bytes
      case (r, i) if byteOrder == ByteOrder.LITTLE_ENDIAN && i % elemSize < nBytes            ⇒ r
      case (r, i) if byteOrder == ByteOrder.BIG_ENDIAN && i % elemSize >= (elemSize - nBytes) ⇒ r
    }).toSeq == builder.result
  }

  def testFloatEncoding(slice: ArraySlice[Float], byteOrder: ByteOrder): Boolean = {
    val elemSize = 4
    val (data, from, to) = slice
    val reference = Array.ofDim[Byte](data.length * elemSize)
    ByteBuffer.wrap(reference).order(byteOrder).asFloatBuffer.put(data)
    val builder = ByteString.newBuilder
    for (i ← 0 until from) builder.putFloat(data(i))(byteOrder)
    builder.putFloats(data, from, to - from)(byteOrder)
    for (i ← to until data.length) builder.putFloat(data(i))(byteOrder)
    reference.toSeq == builder.result
  }

  def testDoubleEncoding(slice: ArraySlice[Double], byteOrder: ByteOrder): Boolean = {
    val elemSize = 8
    val (data, from, to) = slice
    val reference = Array.ofDim[Byte](data.length * elemSize)
    ByteBuffer.wrap(reference).order(byteOrder).asDoubleBuffer.put(data)
    val builder = ByteString.newBuilder
    for (i ← 0 until from) builder.putDouble(data(i))(byteOrder)
    builder.putDoubles(data, from, to - from)(byteOrder)
    for (i ← to until data.length) builder.putDouble(data(i))(byteOrder)
    reference.toSeq == builder.result
  }

  "BinaryIndexedTree" must {
    implicit val BinaryIndexedTreeEquality = new Equality[BinaryIndexedTree] {
      override def areEqual(a: BinaryIndexedTree, b: Any) = b match {
        case it: BinaryIndexedTree ⇒
          a.fullDrops == it.fullDrops &&
            a.fullTakes == it.fullTakes &&
            a.restToDrop == it.restToDrop &&
            a.restToTake == it.restToTake &&
            a.sumTable.deep == it.sumTable.deep &&
            a.elemTable.deep == it.elemTable.deep
        case _ ⇒ false
      }
    }

    "BinaryIndexedTree#createSumTable" in {
      BinaryIndexedTree.createSumTable(Array(1, 2, 3)) should ===(Array(1, 3, 3))
      BinaryIndexedTree.createSumTable(Array(1, 2, 3, 4)) should ===(Array(1, 3, 3, 10))
      BinaryIndexedTree.createSumTable(Array(1, 2, 3, 4, 5)) should ===(Array(1, 3, 3, 10, 5))
      BinaryIndexedTree.createSumTable(Array(1, 2, 3, 4, 5, 6)) should ===(Array(1, 3, 3, 10, 5, 11))
      BinaryIndexedTree.createSumTable(Array(1, 2, 3, 4, 5, 6, 7)) should ===(Array(1, 3, 3, 10, 5, 11, 7))
      BinaryIndexedTree.createSumTable(Array(1, 2, 3, 4, 5, 6, 7, 8)) should ===(Array(1, 3, 3, 10, 5, 11, 7, 36))
      BinaryIndexedTree.createSumTable(Array(1, 2, 3, 4, 5, 6, 7, 8, 9)) should ===(Array(1, 3, 3, 10, 5, 11, 7, 36, 9))
      BinaryIndexedTree.createSumTable(Array(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)) should ===(Array(1, 3, 3, 10, 5, 11, 7, 36, 9, 9))
      BinaryIndexedTree.createSumTable(Array(1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1)) should ===(
        Array(1, 3, 3, 10, 5, 11, 7, 36, 9, 9, 1))
      BinaryIndexedTree.createSumTable(Array(1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2)) should ===(
        Array(1, 3, 3, 10, 5, 11, 7, 36, 9, 9, 1, 12))
      BinaryIndexedTree.createSumTable(Array(1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3)) should ===(
        Array(1, 3, 3, 10, 5, 11, 7, 36, 9, 9, 1, 12, 3))
      BinaryIndexedTree.createSumTable(Array(1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4)) should ===(
        Array(1, 3, 3, 10, 5, 11, 7, 36, 9, 9, 1, 12, 3, 7))
      BinaryIndexedTree.createSumTable(Array(1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5)) should ===(
        Array(1, 3, 3, 10, 5, 11, 7, 36, 9, 9, 1, 12, 3, 7, 5))
      BinaryIndexedTree.createSumTable(Array(1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 6)) should ===(
        Array(1, 3, 3, 10, 5, 11, 7, 36, 9, 9, 1, 12, 3, 7, 5, 66))
    }

    "init from Vector[ByteString1]" in {
      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("a"))) should ===(
        BinaryIndexedTree(0, 0, 1, 0, Array(1), Array(1)))

      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("abc"))) should ===(
        BinaryIndexedTree(0, 0, 1, 0, Array(3), Array(3)))

      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))) should ===(
        BinaryIndexedTree(0, 0, 3, 0, Array(1, 3, 3), Array(1, 2, 3)))

      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"), ByteString1.fromString("ghij"))) should ===(
        BinaryIndexedTree(0, 0, 4, 0, Array(1, 3, 3, 10), Array(1, 2, 3, 4)))

      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString(""),
        ByteString1.fromString("def"), ByteString1.fromString("ghij"))) should ===(
        BinaryIndexedTree(0, 0, 5, 0, Array(1, 3, 0, 6, 4), Array(1, 2, 0, 3, 4)))
    }
    "bound" in {
      val empty = BinaryIndexedTree.empty
      val bit = BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("a")))
      val bit2 = BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("abc")))
      val bit3 = BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def")))

      empty.findLowerBoundIndexByNumberOfElements(0) should ===(0)
      empty.findLowerBoundIndexByNumberOfElements(1) should ===(0)

      bit.findLowerBoundIndexByNumberOfElements(0) should ===(0)
      bit.findLowerBoundIndexByNumberOfElements(1) should ===(0)

      bit2.findLowerBoundIndexByNumberOfElements(0) should ===(0)
      bit2.findLowerBoundIndexByNumberOfElements(1) should ===(0)
      bit2.findLowerBoundIndexByNumberOfElements(2) should ===(0)
      bit2.findLowerBoundIndexByNumberOfElements(3) should ===(0)

      bit3.findLowerBoundIndexByNumberOfElements(0) should ===(0)
      bit3.findLowerBoundIndexByNumberOfElements(1) should ===(0)
      bit3.findLowerBoundIndexByNumberOfElements(2) should ===(1)
      bit3.findLowerBoundIndexByNumberOfElements(3) should ===(1)
      bit3.findLowerBoundIndexByNumberOfElements(4) should ===(2)
      bit3.findLowerBoundIndexByNumberOfElements(5) should ===(2)
      bit3.findLowerBoundIndexByNumberOfElements(6) should ===(2)
    }
    "take" in {
      BinaryIndexedTree.empty.take(-1) should ===(BinaryIndexedTree.empty)
      BinaryIndexedTree.empty.take(0) should ===(BinaryIndexedTree.empty)
      BinaryIndexedTree.empty.take(1) should ===(BinaryIndexedTree.empty)

      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("a"))).take(-1) should ===(
        BinaryIndexedTree.empty)
      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("a"))).take(0) should ===(
        BinaryIndexedTree.empty)
      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("a"))).take(1) should ===(
        BinaryIndexedTree(0, 0, 1, 0, Array(1), Array(1)))
      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("a"))).take(2) should ===(
        BinaryIndexedTree(0, 0, 1, 0, Array(1), Array(1)))

      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("abc"))).take(-1) should ===(
        BinaryIndexedTree.empty)
      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("abc"))).take(0) should ===(
        BinaryIndexedTree.empty)
      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("abc"))).take(1) should ===(
        BinaryIndexedTree(0, 0, 0, 1, Array(3), Array(3)))
      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("abc"))).take(2) should ===(
        BinaryIndexedTree(0, 0, 0, 2, Array(3), Array(3)))
      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("abc"))).take(3) should ===(
        BinaryIndexedTree(0, 0, 1, 0, Array(3), Array(3)))
      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("abc"))).take(4) should ===(
        BinaryIndexedTree(0, 0, 1, 0, Array(3), Array(3)))

      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))).take(-1) should ===(
        BinaryIndexedTree.empty)
      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))).take(0) should ===(
        BinaryIndexedTree.empty)
      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))).take(1) should ===(
        BinaryIndexedTree(0, 0, 1, 0, Array(1, 3, 3), Array(1, 2, 3)))
      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))).take(2) should ===(
        BinaryIndexedTree(0, 0, 1, 1, Array(1, 3, 3), Array(1, 2, 3)))
      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))).take(3) should ===(
        BinaryIndexedTree(0, 0, 2, 0, Array(1, 3, 3), Array(1, 2, 3)))
      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))).take(4) should ===(
        BinaryIndexedTree(0, 0, 2, 1, Array(1, 3, 3), Array(1, 2, 3)))
      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))).take(5) should ===(
        BinaryIndexedTree(0, 0, 2, 2, Array(1, 3, 3), Array(1, 2, 3)))
      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))).take(6) should ===(
        BinaryIndexedTree(0, 0, 3, 0, Array(1, 3, 3), Array(1, 2, 3)))
      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))).take(7) should ===(
        BinaryIndexedTree(0, 0, 3, 0, Array(1, 3, 3), Array(1, 2, 3)))

      val sumTable = Array(1, 3, 3, 10, 5, 11, 7, 36, 9, 19, 1, 22, 3, 7, 5, 76)
      val elemTable = Array(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 1, 2, 3, 4, 5, 6)
      val it16 = BinaryIndexedTree(0, 0, 16, 0, sumTable, elemTable)

      it16.take(60).take(50).take(40) should ===(BinaryIndexedTree(0, 0, 8, 4, sumTable, elemTable))
      it16.take(40).take(50).take(60) should ===(BinaryIndexedTree(0, 0, 8, 4, sumTable, elemTable))
      it16.take(0).take(60).take(50).take(40) should ===(BinaryIndexedTree.empty)
      it16.take(60).take(50).take(40).take(0) should ===(BinaryIndexedTree.empty)

      it16.take(0) should ===(BinaryIndexedTree.empty)
      it16.take(1) should ===(BinaryIndexedTree(0, 0, 1, 0, sumTable, elemTable))
      it16.take(2) should ===(BinaryIndexedTree(0, 0, 1, 1, sumTable, elemTable))
      it16.take(3) should ===(BinaryIndexedTree(0, 0, 2, 0, sumTable, elemTable))
      it16.take(4) should ===(BinaryIndexedTree(0, 0, 2, 1, sumTable, elemTable))
      it16.take(5) should ===(BinaryIndexedTree(0, 0, 2, 2, sumTable, elemTable))
      it16.take(6) should ===(BinaryIndexedTree(0, 0, 3, 0, sumTable, elemTable))
      it16.take(7) should ===(BinaryIndexedTree(0, 0, 3, 1, sumTable, elemTable))
      it16.take(9) should ===(BinaryIndexedTree(0, 0, 3, 3, sumTable, elemTable))
      it16.take(10) should ===(BinaryIndexedTree(0, 0, 4, 0, sumTable, elemTable))
      it16.take(11) should ===(BinaryIndexedTree(0, 0, 4, 1, sumTable, elemTable))
      it16.take(14) should ===(BinaryIndexedTree(0, 0, 4, 4, sumTable, elemTable))
      it16.take(15) should ===(BinaryIndexedTree(0, 0, 5, 0, sumTable, elemTable))
      it16.take(16) should ===(BinaryIndexedTree(0, 0, 5, 1, sumTable, elemTable))
      it16.take(20) should ===(BinaryIndexedTree(0, 0, 5, 5, sumTable, elemTable))
      it16.take(21) should ===(BinaryIndexedTree(0, 0, 6, 0, sumTable, elemTable))
      it16.take(22) should ===(BinaryIndexedTree(0, 0, 6, 1, sumTable, elemTable))
      it16.take(27) should ===(BinaryIndexedTree(0, 0, 6, 6, sumTable, elemTable))
      it16.take(28) should ===(BinaryIndexedTree(0, 0, 7, 0, sumTable, elemTable))
      it16.take(29) should ===(BinaryIndexedTree(0, 0, 7, 1, sumTable, elemTable))
      it16.take(35) should ===(BinaryIndexedTree(0, 0, 7, 7, sumTable, elemTable))
      it16.take(36) should ===(BinaryIndexedTree(0, 0, 8, 0, sumTable, elemTable))
      it16.take(37) should ===(BinaryIndexedTree(0, 0, 8, 1, sumTable, elemTable))
      it16.take(44) should ===(BinaryIndexedTree(0, 0, 8, 8, sumTable, elemTable))
      it16.take(45) should ===(BinaryIndexedTree(0, 0, 9, 0, sumTable, elemTable))
      it16.take(46) should ===(BinaryIndexedTree(0, 0, 9, 1, sumTable, elemTable))
      it16.take(54) should ===(BinaryIndexedTree(0, 0, 9, 9, sumTable, elemTable))
      it16.take(55) should ===(BinaryIndexedTree(0, 0, 10, 0, sumTable, elemTable))
      it16.take(56) should ===(BinaryIndexedTree(0, 0, 11, 0, sumTable, elemTable))
      it16.take(57) should ===(BinaryIndexedTree(0, 0, 11, 1, sumTable, elemTable))
      it16.take(58) should ===(BinaryIndexedTree(0, 0, 12, 0, sumTable, elemTable))
      it16.take(59) should ===(BinaryIndexedTree(0, 0, 12, 1, sumTable, elemTable))
      it16.take(60) should ===(BinaryIndexedTree(0, 0, 12, 2, sumTable, elemTable))
      it16.take(61) should ===(BinaryIndexedTree(0, 0, 13, 0, sumTable, elemTable))
      it16.take(62) should ===(BinaryIndexedTree(0, 0, 13, 1, sumTable, elemTable))
      it16.take(64) should ===(BinaryIndexedTree(0, 0, 13, 3, sumTable, elemTable))
      it16.take(65) should ===(BinaryIndexedTree(0, 0, 14, 0, sumTable, elemTable))
      it16.take(66) should ===(BinaryIndexedTree(0, 0, 14, 1, sumTable, elemTable))
      it16.take(69) should ===(BinaryIndexedTree(0, 0, 14, 4, sumTable, elemTable))
      it16.take(70) should ===(BinaryIndexedTree(0, 0, 15, 0, sumTable, elemTable))
      it16.take(71) should ===(BinaryIndexedTree(0, 0, 15, 1, sumTable, elemTable))
      it16.take(75) should ===(BinaryIndexedTree(0, 0, 15, 5, sumTable, elemTable))
      it16.take(76) should ===(BinaryIndexedTree(0, 0, 16, 0, sumTable, elemTable))

      it16.drop(0).take(1) should ===(BinaryIndexedTree(0, 0, 1, 0, sumTable, elemTable))
      it16.drop(1).take(1) should ===(BinaryIndexedTree(1, 0, 1, 1, sumTable, elemTable))
      it16.drop(2).take(1) should ===(BinaryIndexedTree(1, 1, 2, 0, sumTable, elemTable))
      it16.drop(3).take(1) should ===(BinaryIndexedTree(2, 0, 2, 1, sumTable, elemTable))
      it16.drop(4).take(1) should ===(BinaryIndexedTree(2, 1, 2, 2, sumTable, elemTable))
      it16.drop(5).take(1) should ===(BinaryIndexedTree(2, 2, 3, 0, sumTable, elemTable))
      it16.drop(6).take(1) should ===(BinaryIndexedTree(3, 0, 3, 1, sumTable, elemTable))
      it16.drop(7).take(1) should ===(BinaryIndexedTree(3, 1, 3, 2, sumTable, elemTable))
      it16.drop(9).take(1) should ===(BinaryIndexedTree(3, 3, 4, 0, sumTable, elemTable))
      it16.drop(10).take(1) should ===(BinaryIndexedTree(4, 0, 4, 1, sumTable, elemTable))
      it16.drop(11).take(1) should ===(BinaryIndexedTree(4, 1, 4, 2, sumTable, elemTable))
      it16.drop(14).take(1) should ===(BinaryIndexedTree(4, 4, 5, 0, sumTable, elemTable))
      it16.drop(15).take(1) should ===(BinaryIndexedTree(5, 0, 5, 1, sumTable, elemTable))
      it16.drop(16).take(1) should ===(BinaryIndexedTree(5, 1, 5, 2, sumTable, elemTable))
      it16.drop(20).take(1) should ===(BinaryIndexedTree(5, 5, 6, 0, sumTable, elemTable))
      it16.drop(21).take(1) should ===(BinaryIndexedTree(6, 0, 6, 1, sumTable, elemTable))
      it16.drop(22).take(1) should ===(BinaryIndexedTree(6, 1, 6, 2, sumTable, elemTable))
      it16.drop(27).take(1) should ===(BinaryIndexedTree(6, 6, 7, 0, sumTable, elemTable))
      it16.drop(28).take(1) should ===(BinaryIndexedTree(7, 0, 7, 1, sumTable, elemTable))
      it16.drop(29).take(1) should ===(BinaryIndexedTree(7, 1, 7, 2, sumTable, elemTable))
      it16.drop(35).take(1) should ===(BinaryIndexedTree(7, 7, 8, 0, sumTable, elemTable))
      it16.drop(36).take(1) should ===(BinaryIndexedTree(8, 0, 8, 1, sumTable, elemTable))
      it16.drop(37).take(1) should ===(BinaryIndexedTree(8, 1, 8, 2, sumTable, elemTable))
      it16.drop(44).take(1) should ===(BinaryIndexedTree(8, 8, 9, 0, sumTable, elemTable))
      it16.drop(45).take(1) should ===(BinaryIndexedTree(9, 0, 9, 1, sumTable, elemTable))
      it16.drop(46).take(1) should ===(BinaryIndexedTree(9, 1, 9, 2, sumTable, elemTable))
      it16.drop(54).take(1) should ===(BinaryIndexedTree(9, 9, 10, 0, sumTable, elemTable))
      it16.drop(55).take(1) should ===(BinaryIndexedTree(10, 0, 11, 0, sumTable, elemTable))
      it16.drop(56).take(1) should ===(BinaryIndexedTree(11, 0, 11, 1, sumTable, elemTable))
      it16.drop(57).take(1) should ===(BinaryIndexedTree(11, 1, 12, 0, sumTable, elemTable))
      it16.drop(58).take(1) should ===(BinaryIndexedTree(12, 0, 12, 1, sumTable, elemTable))
      it16.drop(59).take(1) should ===(BinaryIndexedTree(12, 1, 12, 2, sumTable, elemTable))
      it16.drop(60).take(1) should ===(BinaryIndexedTree(12, 2, 13, 0, sumTable, elemTable))
      it16.drop(61).take(1) should ===(BinaryIndexedTree(13, 0, 13, 1, sumTable, elemTable))
      it16.drop(62).take(1) should ===(BinaryIndexedTree(13, 1, 13, 2, sumTable, elemTable))
      it16.drop(64).take(1) should ===(BinaryIndexedTree(13, 3, 14, 0, sumTable, elemTable))
      it16.drop(65).take(1) should ===(BinaryIndexedTree(14, 0, 14, 1, sumTable, elemTable))
      it16.drop(66).take(1) should ===(BinaryIndexedTree(14, 1, 14, 2, sumTable, elemTable))
      it16.drop(69).take(1) should ===(BinaryIndexedTree(14, 4, 15, 0, sumTable, elemTable))
      it16.drop(70).take(1) should ===(BinaryIndexedTree(15, 0, 15, 1, sumTable, elemTable))
      it16.drop(71).take(1) should ===(BinaryIndexedTree(15, 1, 15, 2, sumTable, elemTable))
      it16.drop(75).take(1) should ===(BinaryIndexedTree(15, 5, 16, 0, sumTable, elemTable))
      it16.drop(76).take(1) should ===(BinaryIndexedTree.empty)
    }
    "drop" in {
      BinaryIndexedTree.empty.drop(-1) should ===(BinaryIndexedTree.empty)
      BinaryIndexedTree.empty.drop(0) should ===(BinaryIndexedTree.empty)
      BinaryIndexedTree.empty.drop(1) should ===(BinaryIndexedTree.empty)

      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("a"))).drop(-1) should ===(
        BinaryIndexedTree(0, 0, 1, 0, Array(1), Array(1)))
      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("a"))).drop(0) should ===(
        BinaryIndexedTree(0, 0, 1, 0, Array(1), Array(1)))
      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("a"))).drop(1) should ===(
        BinaryIndexedTree.empty)
      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("a"))).drop(2) should ===(
        BinaryIndexedTree.empty)

      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("abc"))).drop(-1) should ===(
        BinaryIndexedTree(0, 0, 1, 0, Array(3), Array(3)))
      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("abc"))).drop(0) should ===(
        BinaryIndexedTree(0, 0, 1, 0, Array(3), Array(3)))
      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("abc"))).drop(1) should ===(
        BinaryIndexedTree(0, 1, 1, 0, Array(3), Array(3)))
      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("abc"))).drop(2) should ===(
        BinaryIndexedTree(0, 2, 1, 0, Array(3), Array(3)))
      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("abc"))).drop(3) should ===(
        BinaryIndexedTree.empty)
      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("abc"))).drop(4) should ===(
        BinaryIndexedTree.empty)

      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))).drop(-1) should ===(
        BinaryIndexedTree(0, 0, 3, 0, Array(1, 3, 3), Array(1, 2, 3)))
      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))).drop(0) should ===(
        BinaryIndexedTree(0, 0, 3, 0, Array(1, 3, 3), Array(1, 2, 3)))
      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))).drop(1) should ===(
        BinaryIndexedTree(1, 0, 3, 0, Array(1, 3, 3), Array(1, 2, 3)))
      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))).drop(2) should ===(
        BinaryIndexedTree(1, 1, 3, 0, Array(1, 3, 3), Array(1, 2, 3)))
      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))).drop(3) should ===(
        BinaryIndexedTree(2, 0, 3, 0, Array(1, 3, 3), Array(1, 2, 3)))
      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))).drop(4) should ===(
        BinaryIndexedTree(2, 1, 3, 0, Array(1, 3, 3), Array(1, 2, 3)))
      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))).drop(5) should ===(
        BinaryIndexedTree(2, 2, 3, 0, Array(1, 3, 3), Array(1, 2, 3)))
      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))).drop(6) should ===(
        BinaryIndexedTree.empty)
      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))).drop(7) should ===(
        BinaryIndexedTree.empty)

      val sumTable = Array(1, 3, 3, 10, 5, 11, 7, 36, 9, 19, 1, 22, 3, 7, 5, 76)
      val elemTable = Array(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 1, 2, 3, 4, 5, 6)
      val bit16 = BinaryIndexedTree(0, 0, 16, 0, sumTable, elemTable)

      bit16.drop(10).drop(20).drop(30) should ===(BinaryIndexedTree(12, 2, 16, 0, sumTable, elemTable))
      bit16.drop(30).drop(20).drop(10) should ===(BinaryIndexedTree(12, 2, 16, 0, sumTable, elemTable))
      bit16.drop(0).drop(60).drop(50).drop(40) should ===(BinaryIndexedTree.empty)
      bit16.drop(60).drop(50).drop(40).drop(0) should ===(BinaryIndexedTree.empty)

      bit16.drop(0) should ===(BinaryIndexedTree(0, 0, 16, 0, sumTable, elemTable))
      bit16.drop(1) should ===(BinaryIndexedTree(1, 0, 16, 0, sumTable, elemTable))
      bit16.drop(2) should ===(BinaryIndexedTree(1, 1, 16, 0, sumTable, elemTable))
      bit16.drop(3) should ===(BinaryIndexedTree(2, 0, 16, 0, sumTable, elemTable))
      bit16.drop(4) should ===(BinaryIndexedTree(2, 1, 16, 0, sumTable, elemTable))
      bit16.drop(5) should ===(BinaryIndexedTree(2, 2, 16, 0, sumTable, elemTable))
      bit16.drop(6) should ===(BinaryIndexedTree(3, 0, 16, 0, sumTable, elemTable))
      bit16.drop(7) should ===(BinaryIndexedTree(3, 1, 16, 0, sumTable, elemTable))
      bit16.drop(9) should ===(BinaryIndexedTree(3, 3, 16, 0, sumTable, elemTable))
      bit16.drop(10) should ===(BinaryIndexedTree(4, 0, 16, 0, sumTable, elemTable))
      bit16.drop(11) should ===(BinaryIndexedTree(4, 1, 16, 0, sumTable, elemTable))
      bit16.drop(14) should ===(BinaryIndexedTree(4, 4, 16, 0, sumTable, elemTable))
      bit16.drop(15) should ===(BinaryIndexedTree(5, 0, 16, 0, sumTable, elemTable))
      bit16.drop(16) should ===(BinaryIndexedTree(5, 1, 16, 0, sumTable, elemTable))
      bit16.drop(20) should ===(BinaryIndexedTree(5, 5, 16, 0, sumTable, elemTable))
      bit16.drop(21) should ===(BinaryIndexedTree(6, 0, 16, 0, sumTable, elemTable))
      bit16.drop(22) should ===(BinaryIndexedTree(6, 1, 16, 0, sumTable, elemTable))
      bit16.drop(27) should ===(BinaryIndexedTree(6, 6, 16, 0, sumTable, elemTable))
      bit16.drop(28) should ===(BinaryIndexedTree(7, 0, 16, 0, sumTable, elemTable))
      bit16.drop(29) should ===(BinaryIndexedTree(7, 1, 16, 0, sumTable, elemTable))
      bit16.drop(35) should ===(BinaryIndexedTree(7, 7, 16, 0, sumTable, elemTable))
      bit16.drop(36) should ===(BinaryIndexedTree(8, 0, 16, 0, sumTable, elemTable))
      bit16.drop(37) should ===(BinaryIndexedTree(8, 1, 16, 0, sumTable, elemTable))
      bit16.drop(44) should ===(BinaryIndexedTree(8, 8, 16, 0, sumTable, elemTable))
      bit16.drop(45) should ===(BinaryIndexedTree(9, 0, 16, 0, sumTable, elemTable))
      bit16.drop(46) should ===(BinaryIndexedTree(9, 1, 16, 0, sumTable, elemTable))
      bit16.drop(54) should ===(BinaryIndexedTree(9, 9, 16, 0, sumTable, elemTable))
      bit16.drop(55) should ===(BinaryIndexedTree(10, 0, 16, 0, sumTable, elemTable))
      bit16.drop(56) should ===(BinaryIndexedTree(11, 0, 16, 0, sumTable, elemTable))
      bit16.drop(57) should ===(BinaryIndexedTree(11, 1, 16, 0, sumTable, elemTable))
      bit16.drop(58) should ===(BinaryIndexedTree(12, 0, 16, 0, sumTable, elemTable))
      bit16.drop(59) should ===(BinaryIndexedTree(12, 1, 16, 0, sumTable, elemTable))
      bit16.drop(60) should ===(BinaryIndexedTree(12, 2, 16, 0, sumTable, elemTable))
      bit16.drop(61) should ===(BinaryIndexedTree(13, 0, 16, 0, sumTable, elemTable))
      bit16.drop(62) should ===(BinaryIndexedTree(13, 1, 16, 0, sumTable, elemTable))
      bit16.drop(64) should ===(BinaryIndexedTree(13, 3, 16, 0, sumTable, elemTable))
      bit16.drop(65) should ===(BinaryIndexedTree(14, 0, 16, 0, sumTable, elemTable))
      bit16.drop(66) should ===(BinaryIndexedTree(14, 1, 16, 0, sumTable, elemTable))
      bit16.drop(69) should ===(BinaryIndexedTree(14, 4, 16, 0, sumTable, elemTable))
      bit16.drop(70) should ===(BinaryIndexedTree(15, 0, 16, 0, sumTable, elemTable))
      bit16.drop(71) should ===(BinaryIndexedTree(15, 1, 16, 0, sumTable, elemTable))
      bit16.drop(75) should ===(BinaryIndexedTree(15, 5, 16, 0, sumTable, elemTable))
      bit16.drop(76) should ===(BinaryIndexedTree.empty)

      bit16.take(0).drop(1) should ===(BinaryIndexedTree.empty)
      bit16.take(1).drop(0) should ===(BinaryIndexedTree(0, 0, 1, 0, sumTable, elemTable))
      bit16.take(2).drop(1) should ===(BinaryIndexedTree(1, 0, 1, 1, sumTable, elemTable))
      bit16.take(3).drop(2) should ===(BinaryIndexedTree(1, 1, 2, 0, sumTable, elemTable))
      bit16.take(4).drop(3) should ===(BinaryIndexedTree(2, 0, 2, 1, sumTable, elemTable))
      bit16.take(5).drop(4) should ===(BinaryIndexedTree(2, 1, 2, 2, sumTable, elemTable))
      bit16.take(6).drop(5) should ===(BinaryIndexedTree(2, 2, 3, 0, sumTable, elemTable))
      bit16.take(7).drop(6) should ===(BinaryIndexedTree(3, 0, 3, 1, sumTable, elemTable))
      bit16.take(9).drop(8) should ===(BinaryIndexedTree(3, 2, 3, 3, sumTable, elemTable))
      bit16.take(10).drop(9) should ===(BinaryIndexedTree(3, 3, 4, 0, sumTable, elemTable))
      bit16.take(11).drop(10) should ===(BinaryIndexedTree(4, 0, 4, 1, sumTable, elemTable))
      bit16.take(14).drop(13) should ===(BinaryIndexedTree(4, 3, 4, 4, sumTable, elemTable))
      bit16.take(15).drop(14) should ===(BinaryIndexedTree(4, 4, 5, 0, sumTable, elemTable))
      bit16.take(16).drop(15) should ===(BinaryIndexedTree(5, 0, 5, 1, sumTable, elemTable))
      bit16.take(20).drop(19) should ===(BinaryIndexedTree(5, 4, 5, 5, sumTable, elemTable))
      bit16.take(21).drop(20) should ===(BinaryIndexedTree(5, 5, 6, 0, sumTable, elemTable))
      bit16.take(22).drop(21) should ===(BinaryIndexedTree(6, 0, 6, 1, sumTable, elemTable))
      bit16.take(27).drop(26) should ===(BinaryIndexedTree(6, 5, 6, 6, sumTable, elemTable))
      bit16.take(28).drop(27) should ===(BinaryIndexedTree(6, 6, 7, 0, sumTable, elemTable))
      bit16.take(29).drop(28) should ===(BinaryIndexedTree(7, 0, 7, 1, sumTable, elemTable))
      bit16.take(35).drop(34) should ===(BinaryIndexedTree(7, 6, 7, 7, sumTable, elemTable))
      bit16.take(36).drop(35) should ===(BinaryIndexedTree(7, 7, 8, 0, sumTable, elemTable))
      bit16.take(37).drop(36) should ===(BinaryIndexedTree(8, 0, 8, 1, sumTable, elemTable))
      bit16.take(44).drop(43) should ===(BinaryIndexedTree(8, 7, 8, 8, sumTable, elemTable))
      bit16.take(45).drop(44) should ===(BinaryIndexedTree(8, 8, 9, 0, sumTable, elemTable))
      bit16.take(46).drop(45) should ===(BinaryIndexedTree(9, 0, 9, 1, sumTable, elemTable))
      bit16.take(54).drop(53) should ===(BinaryIndexedTree(9, 8, 9, 9, sumTable, elemTable))
      bit16.take(55).drop(54) should ===(BinaryIndexedTree(9, 9, 10, 0, sumTable, elemTable))
      bit16.take(56).drop(55) should ===(BinaryIndexedTree(10, 0, 11, 0, sumTable, elemTable))
      bit16.take(57).drop(56) should ===(BinaryIndexedTree(11, 0, 11, 1, sumTable, elemTable))
      bit16.take(58).drop(57) should ===(BinaryIndexedTree(11, 1, 12, 0, sumTable, elemTable))
      bit16.take(59).drop(58) should ===(BinaryIndexedTree(12, 0, 12, 1, sumTable, elemTable))
      bit16.take(60).drop(59) should ===(BinaryIndexedTree(12, 1, 12, 2, sumTable, elemTable))
      bit16.take(61).drop(60) should ===(BinaryIndexedTree(12, 2, 13, 0, sumTable, elemTable))
      bit16.take(62).drop(61) should ===(BinaryIndexedTree(13, 0, 13, 1, sumTable, elemTable))
      bit16.take(64).drop(63) should ===(BinaryIndexedTree(13, 2, 13, 3, sumTable, elemTable))
      bit16.take(65).drop(64) should ===(BinaryIndexedTree(13, 3, 14, 0, sumTable, elemTable))
      bit16.take(66).drop(65) should ===(BinaryIndexedTree(14, 0, 14, 1, sumTable, elemTable))
      bit16.take(69).drop(68) should ===(BinaryIndexedTree(14, 3, 14, 4, sumTable, elemTable))
      bit16.take(70).drop(69) should ===(BinaryIndexedTree(14, 4, 15, 0, sumTable, elemTable))
      bit16.take(71).drop(70) should ===(BinaryIndexedTree(15, 0, 15, 1, sumTable, elemTable))
      bit16.take(75).drop(74) should ===(BinaryIndexedTree(15, 4, 15, 5, sumTable, elemTable))
      bit16.take(76).drop(75) should ===(BinaryIndexedTree(15, 5, 16, 0, sumTable, elemTable))
      bit16.take(77).drop(76) should ===(BinaryIndexedTree.empty)
    }
    "slice" in {
      BinaryIndexedTree.empty.slice(-2, -1) should ===(BinaryIndexedTree.empty)
      BinaryIndexedTree.empty.slice(-1, 0) should ===(BinaryIndexedTree.empty)
      BinaryIndexedTree.empty.slice(-1, 1) should ===(BinaryIndexedTree.empty)
      BinaryIndexedTree.empty.slice(0, -1) should ===(BinaryIndexedTree.empty)
      BinaryIndexedTree.empty.slice(0, 0) should ===(BinaryIndexedTree.empty)
      BinaryIndexedTree.empty.slice(0, 1) should ===(BinaryIndexedTree.empty)
      BinaryIndexedTree.empty.slice(1, -1) should ===(BinaryIndexedTree.empty)
      BinaryIndexedTree.empty.slice(1, 0) should ===(BinaryIndexedTree.empty)
      BinaryIndexedTree.empty.slice(1, 1) should ===(BinaryIndexedTree.empty)

      val bit = BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("a")))
      bit.slice(-1, 0) should ===(BinaryIndexedTree.empty)
      bit.slice(-1, 1) should ===(BinaryIndexedTree(0, 0, 1, 0, Array(1), Array(1)))
      bit.slice(-1, 2) should ===(BinaryIndexedTree(0, 0, 1, 0, Array(1), Array(1)))
      bit.slice(0, 0) should ===(BinaryIndexedTree.empty)
      bit.slice(0, 1) should ===(BinaryIndexedTree(0, 0, 1, 0, Array(1), Array(1)))
      bit.slice(0, 2) should ===(BinaryIndexedTree(0, 0, 1, 0, Array(1), Array(1)))
      bit.slice(1, 0) should ===(BinaryIndexedTree.empty)
      bit.slice(1, 1) should ===(BinaryIndexedTree.empty)
      bit.slice(1, 2) should ===(BinaryIndexedTree.empty)
      bit.slice(2, 0) should ===(BinaryIndexedTree.empty)
      bit.slice(2, 1) should ===(BinaryIndexedTree.empty)
      bit.slice(2, 2) should ===(BinaryIndexedTree.empty)

      val bit2 = BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("abc")))
      bit2.slice(-1, 0) should ===(BinaryIndexedTree.empty)
      bit2.slice(-1, 1) should ===(BinaryIndexedTree(0, 0, 0, 1, Array(3), Array(3)))
      bit2.slice(-1, 2) should ===(BinaryIndexedTree(0, 0, 0, 2, Array(3), Array(3)))
      bit2.slice(-1, 3) should ===(BinaryIndexedTree(0, 0, 1, 0, Array(3), Array(3)))
      bit2.slice(-1, 4) should ===(BinaryIndexedTree(0, 0, 1, 0, Array(3), Array(3)))
      bit2.slice(0, 0) should ===(BinaryIndexedTree.empty)
      bit2.slice(0, 1) should ===(BinaryIndexedTree(0, 0, 0, 1, Array(3), Array(3)))
      bit2.slice(0, 2) should ===(BinaryIndexedTree(0, 0, 0, 2, Array(3), Array(3)))
      bit2.slice(0, 3) should ===(BinaryIndexedTree(0, 0, 1, 0, Array(3), Array(3)))
      bit2.slice(0, 4) should ===(BinaryIndexedTree(0, 0, 1, 0, Array(3), Array(3)))
      bit2.slice(1, -1) should ===(BinaryIndexedTree.empty)
      bit2.slice(1, 0) should ===(BinaryIndexedTree.empty)
      bit2.slice(1, 1) should ===(BinaryIndexedTree.empty)
      bit2.slice(1, 2) should ===(BinaryIndexedTree(0, 1, 0, 2, Array(3), Array(3)))
      bit2.slice(1, 3) should ===(BinaryIndexedTree(0, 1, 1, 0, Array(3), Array(3)))
      bit2.slice(1, 4) should ===(BinaryIndexedTree(0, 1, 1, 0, Array(3), Array(3)))
      bit2.slice(2, -1) should ===(BinaryIndexedTree.empty)
      bit2.slice(2, 0) should ===(BinaryIndexedTree.empty)
      bit2.slice(2, 1) should ===(BinaryIndexedTree.empty)
      bit2.slice(2, 2) should ===(BinaryIndexedTree.empty)
      bit2.slice(2, 3) should ===(BinaryIndexedTree(0, 2, 1, 0, Array(3), Array(3)))
      bit2.slice(2, 4) should ===(BinaryIndexedTree(0, 2, 1, 0, Array(3), Array(3)))
      bit2.slice(3, -1) should ===(BinaryIndexedTree.empty)
      bit2.slice(3, 0) should ===(BinaryIndexedTree.empty)
      bit2.slice(3, 1) should ===(BinaryIndexedTree.empty)
      bit2.slice(3, 2) should ===(BinaryIndexedTree.empty)
      bit2.slice(3, 3) should ===(BinaryIndexedTree.empty)
      bit2.slice(3, 4) should ===(BinaryIndexedTree.empty)

      val sumTable = Array(1, 3, 3, 10, 5, 11, 7, 36, 9, 19, 1, 22, 3, 7, 5, 76)
      val elemTable = Array(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 1, 2, 3, 4, 5, 6)
      val bit16 = BinaryIndexedTree(0, 0, 16, 0, sumTable, elemTable)

      bit16.slice(0, 76) should ===(BinaryIndexedTree(0, 0, 16, 0, sumTable, elemTable))
      bit16.slice(0, 1) should ===(BinaryIndexedTree(0, 0, 1, 0, sumTable, elemTable))
      bit16.slice(1, 2) should ===(BinaryIndexedTree(1, 0, 1, 1, sumTable, elemTable))
      bit16.slice(2, 3) should ===(BinaryIndexedTree(1, 1, 2, 0, sumTable, elemTable))
      bit16.slice(3, 4) should ===(BinaryIndexedTree(2, 0, 2, 1, sumTable, elemTable))
      bit16.slice(4, 5) should ===(BinaryIndexedTree(2, 1, 2, 2, sumTable, elemTable))
      bit16.slice(5, 6) should ===(BinaryIndexedTree(2, 2, 3, 0, sumTable, elemTable))
      bit16.slice(6, 7) should ===(BinaryIndexedTree(3, 0, 3, 1, sumTable, elemTable))
      bit16.slice(7, 9) should ===(BinaryIndexedTree(3, 1, 3, 3, sumTable, elemTable))
      bit16.slice(9, 10) should ===(BinaryIndexedTree(3, 3, 4, 0, sumTable, elemTable))
      bit16.slice(10, 11) should ===(BinaryIndexedTree(4, 0, 4, 1, sumTable, elemTable))
      bit16.slice(11, 14) should ===(BinaryIndexedTree(4, 1, 4, 4, sumTable, elemTable))
      bit16.slice(14, 15) should ===(BinaryIndexedTree(4, 4, 5, 0, sumTable, elemTable))
      bit16.slice(15, 16) should ===(BinaryIndexedTree(5, 0, 5, 1, sumTable, elemTable))
      bit16.slice(16, 20) should ===(BinaryIndexedTree(5, 1, 5, 5, sumTable, elemTable))
      bit16.slice(20, 21) should ===(BinaryIndexedTree(5, 5, 6, 0, sumTable, elemTable))
      bit16.slice(21, 22) should ===(BinaryIndexedTree(6, 0, 6, 1, sumTable, elemTable))
      bit16.slice(22, 27) should ===(BinaryIndexedTree(6, 1, 6, 6, sumTable, elemTable))
      bit16.slice(27, 28) should ===(BinaryIndexedTree(6, 6, 7, 0, sumTable, elemTable))
      bit16.slice(28, 29) should ===(BinaryIndexedTree(7, 0, 7, 1, sumTable, elemTable))
      bit16.slice(29, 35) should ===(BinaryIndexedTree(7, 1, 7, 7, sumTable, elemTable))
      bit16.slice(35, 36) should ===(BinaryIndexedTree(7, 7, 8, 0, sumTable, elemTable))
      bit16.slice(36, 37) should ===(BinaryIndexedTree(8, 0, 8, 1, sumTable, elemTable))
      bit16.slice(37, 44) should ===(BinaryIndexedTree(8, 1, 8, 8, sumTable, elemTable))
      bit16.slice(44, 45) should ===(BinaryIndexedTree(8, 8, 9, 0, sumTable, elemTable))
      bit16.slice(45, 46) should ===(BinaryIndexedTree(9, 0, 9, 1, sumTable, elemTable))
      bit16.slice(46, 54) should ===(BinaryIndexedTree(9, 1, 9, 9, sumTable, elemTable))
      bit16.slice(54, 55) should ===(BinaryIndexedTree(9, 9, 10, 0, sumTable, elemTable))
      bit16.slice(55, 56) should ===(BinaryIndexedTree(10, 0, 11, 0, sumTable, elemTable))
      bit16.slice(56, 57) should ===(BinaryIndexedTree(11, 0, 11, 1, sumTable, elemTable))
      bit16.slice(57, 58) should ===(BinaryIndexedTree(11, 1, 12, 0, sumTable, elemTable))
      bit16.slice(58, 59) should ===(BinaryIndexedTree(12, 0, 12, 1, sumTable, elemTable))
      bit16.slice(59, 60) should ===(BinaryIndexedTree(12, 1, 12, 2, sumTable, elemTable))
      bit16.slice(60, 61) should ===(BinaryIndexedTree(12, 2, 13, 0, sumTable, elemTable))
      bit16.slice(61, 62) should ===(BinaryIndexedTree(13, 0, 13, 1, sumTable, elemTable))
      bit16.slice(62, 64) should ===(BinaryIndexedTree(13, 1, 13, 3, sumTable, elemTable))
      bit16.slice(64, 65) should ===(BinaryIndexedTree(13, 3, 14, 0, sumTable, elemTable))
      bit16.slice(65, 66) should ===(BinaryIndexedTree(14, 0, 14, 1, sumTable, elemTable))
      bit16.slice(66, 69) should ===(BinaryIndexedTree(14, 1, 14, 4, sumTable, elemTable))
      bit16.slice(69, 70) should ===(BinaryIndexedTree(14, 4, 15, 0, sumTable, elemTable))
      bit16.slice(70, 71) should ===(BinaryIndexedTree(15, 0, 15, 1, sumTable, elemTable))
      bit16.slice(71, 75) should ===(BinaryIndexedTree(15, 1, 15, 5, sumTable, elemTable))
      bit16.slice(75, 76) should ===(BinaryIndexedTree(15, 5, 16, 0, sumTable, elemTable))
      bit16.slice(76, 75) should ===(BinaryIndexedTree.empty)
      bit16.slice(76, 77) should ===(BinaryIndexedTree.empty)

      bit16.slice(1, 75).slice(0, 1) should ===(BinaryIndexedTree(1, 0, 1, 1, sumTable, elemTable))
      bit16.slice(1, 75).slice(1, 2) should ===(BinaryIndexedTree(1, 1, 2, 0, sumTable, elemTable))
      bit16.slice(1, 75).slice(2, 3) should ===(BinaryIndexedTree(2, 0, 2, 1, sumTable, elemTable))
      bit16.slice(1, 75).slice(3, 4) should ===(BinaryIndexedTree(2, 1, 2, 2, sumTable, elemTable))
      bit16.slice(1, 75).slice(4, 5) should ===(BinaryIndexedTree(2, 2, 3, 0, sumTable, elemTable))
      bit16.slice(1, 75).slice(5, 6) should ===(BinaryIndexedTree(3, 0, 3, 1, sumTable, elemTable))
      bit16.slice(1, 75).slice(6, 8) should ===(BinaryIndexedTree(3, 1, 3, 3, sumTable, elemTable))
      bit16.slice(1, 75).slice(8, 9) should ===(BinaryIndexedTree(3, 3, 4, 0, sumTable, elemTable))
      bit16.slice(1, 75).slice(9, 10) should ===(BinaryIndexedTree(4, 0, 4, 1, sumTable, elemTable))
      bit16.slice(1, 75).slice(10, 13) should ===(BinaryIndexedTree(4, 1, 4, 4, sumTable, elemTable))
      bit16.slice(1, 75).slice(13, 14) should ===(BinaryIndexedTree(4, 4, 5, 0, sumTable, elemTable))
      bit16.slice(1, 75).slice(14, 15) should ===(BinaryIndexedTree(5, 0, 5, 1, sumTable, elemTable))
      bit16.slice(1, 75).slice(15, 19) should ===(BinaryIndexedTree(5, 1, 5, 5, sumTable, elemTable))
      bit16.slice(1, 75).slice(19, 20) should ===(BinaryIndexedTree(5, 5, 6, 0, sumTable, elemTable))
      bit16.slice(1, 75).slice(20, 21) should ===(BinaryIndexedTree(6, 0, 6, 1, sumTable, elemTable))
      bit16.slice(1, 75).slice(21, 26) should ===(BinaryIndexedTree(6, 1, 6, 6, sumTable, elemTable))
      bit16.slice(1, 75).slice(26, 27) should ===(BinaryIndexedTree(6, 6, 7, 0, sumTable, elemTable))
      bit16.slice(1, 75).slice(27, 28) should ===(BinaryIndexedTree(7, 0, 7, 1, sumTable, elemTable))
      bit16.slice(1, 75).slice(28, 34) should ===(BinaryIndexedTree(7, 1, 7, 7, sumTable, elemTable))
      bit16.slice(1, 75).slice(34, 35) should ===(BinaryIndexedTree(7, 7, 8, 0, sumTable, elemTable))
      bit16.slice(1, 75).slice(35, 36) should ===(BinaryIndexedTree(8, 0, 8, 1, sumTable, elemTable))
      bit16.slice(1, 75).slice(36, 43) should ===(BinaryIndexedTree(8, 1, 8, 8, sumTable, elemTable))
      bit16.slice(1, 75).slice(43, 44) should ===(BinaryIndexedTree(8, 8, 9, 0, sumTable, elemTable))
      bit16.slice(1, 75).slice(44, 45) should ===(BinaryIndexedTree(9, 0, 9, 1, sumTable, elemTable))
      bit16.slice(1, 75).slice(45, 53) should ===(BinaryIndexedTree(9, 1, 9, 9, sumTable, elemTable))
      bit16.slice(1, 75).slice(53, 54) should ===(BinaryIndexedTree(9, 9, 10, 0, sumTable, elemTable))
      bit16.slice(1, 75).slice(54, 55) should ===(BinaryIndexedTree(10, 0, 11, 0, sumTable, elemTable))
      bit16.slice(1, 75).slice(55, 56) should ===(BinaryIndexedTree(11, 0, 11, 1, sumTable, elemTable))
      bit16.slice(1, 75).slice(56, 57) should ===(BinaryIndexedTree(11, 1, 12, 0, sumTable, elemTable))
      bit16.slice(1, 75).slice(57, 58) should ===(BinaryIndexedTree(12, 0, 12, 1, sumTable, elemTable))
      bit16.slice(1, 75).slice(58, 59) should ===(BinaryIndexedTree(12, 1, 12, 2, sumTable, elemTable))
      bit16.slice(1, 75).slice(59, 60) should ===(BinaryIndexedTree(12, 2, 13, 0, sumTable, elemTable))
      bit16.slice(1, 75).slice(60, 61) should ===(BinaryIndexedTree(13, 0, 13, 1, sumTable, elemTable))
      bit16.slice(1, 75).slice(61, 63) should ===(BinaryIndexedTree(13, 1, 13, 3, sumTable, elemTable))
      bit16.slice(1, 75).slice(63, 64) should ===(BinaryIndexedTree(13, 3, 14, 0, sumTable, elemTable))
      bit16.slice(1, 75).slice(64, 65) should ===(BinaryIndexedTree(14, 0, 14, 1, sumTable, elemTable))
      bit16.slice(1, 75).slice(65, 68) should ===(BinaryIndexedTree(14, 1, 14, 4, sumTable, elemTable))
      bit16.slice(1, 75).slice(68, 69) should ===(BinaryIndexedTree(14, 4, 15, 0, sumTable, elemTable))
      bit16.slice(1, 75).slice(69, 70) should ===(BinaryIndexedTree(15, 0, 15, 1, sumTable, elemTable))
      bit16.slice(1, 75).slice(70, 75) should ===(BinaryIndexedTree(15, 1, 15, 5, sumTable, elemTable))
      bit16.slice(1, 75).slice(70, 76) should ===(BinaryIndexedTree(15, 1, 15, 5, sumTable, elemTable))
      bit16.slice(1, 75).slice(75, 76) should ===(BinaryIndexedTree.empty)
    }
    "append" in {
      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("a"))) ++ BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("a"))) should ===(
        BinaryIndexedTree(0, 0, 2, 0, Array(1, 2), Array(1, 1)))

      val take9drop1: BinaryIndexedTree = BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"), ByteString1.fromString("ghij"))).take(9).drop(1)
      val take8drop2 = BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"), ByteString1.fromString("ghij"))).take(8).drop(2)
      val take7drop3 = BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"), ByteString1.fromString("ghij"))).take(7).drop(3)

      take9drop1 ++ take9drop1 should ===(BinaryIndexedTree(0, 0, 6, 0, Array(2, 5, 3, 10, 3, 6), Array(2, 3, 3, 2, 3, 3)))
      take9drop1 ++ take8drop2 should ===(BinaryIndexedTree(0, 0, 6, 0, Array(2, 5, 3, 9, 3, 5), Array(2, 3, 3, 1, 3, 2)))
      take9drop1 ++ take7drop3 should ===(BinaryIndexedTree(0, 0, 5, 0, Array(2, 5, 3, 11, 1), Array(2, 3, 3, 3, 1)))
      take8drop2 ++ take9drop1 should ===(BinaryIndexedTree(0, 0, 6, 0, Array(1, 4, 2, 8, 3, 6), Array(1, 3, 2, 2, 3, 3)))
      take8drop2 ++ take8drop2 should ===(BinaryIndexedTree(0, 0, 6, 0, Array(1, 4, 2, 7, 3, 5), Array(1, 3, 2, 1, 3, 2)))
      take8drop2 ++ take7drop3 should ===(BinaryIndexedTree(0, 0, 5, 0, Array(1, 4, 2, 9, 1), Array(1, 3, 2, 3, 1)))
      take7drop3 ++ take9drop1 should ===(BinaryIndexedTree(0, 0, 5, 0, Array(3, 4, 2, 9, 3), Array(3, 1, 2, 3, 3)))
      take7drop3 ++ take8drop2 should ===(BinaryIndexedTree(0, 0, 5, 0, Array(3, 4, 1, 8, 2), Array(3, 1, 1, 3, 2)))
      take7drop3 ++ take7drop3 should ===(BinaryIndexedTree(0, 0, 4, 0, Array(3, 4, 3, 8), Array(3, 1, 3, 1)))

      val bit = BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"),
        ByteString1.fromString("bc"),
        ByteString1.fromString("def"),
        ByteString1.fromString("ghij"),
        ByteString1.fromString("1"),
        ByteString1.fromString("23"),
        ByteString1.fromString("456"),
        ByteString1.fromString("7890")
      ))
      bit ++ bit should ===(
        BinaryIndexedTree(0, 0, 16, 0,
          Array(1, 3, 3, 10, 1, 3, 3, 20, 1, 3, 3, 10, 1, 3, 3, 40),
          Array(1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3, 4)))
    }
    "append an element" in {
      BinaryIndexedTree.empty :+ 0 should ===(BinaryIndexedTree.empty)
      BinaryIndexedTree.empty :+ 1 should ===(BinaryIndexedTree(0, 0, 1, 0, Array(1), Array(1)))

      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("a"))) :+ 0 should ===(
        BinaryIndexedTree(0, 0, 1, 0, Array(1), Array(1)))
      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("a"))) :+ 1 should ===(
        BinaryIndexedTree(0, 0, 2, 0, Array(1, 2), Array(1, 1)))

      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("abc"))) :+ 0 should ===(
        BinaryIndexedTree(0, 0, 1, 0, Array(3), Array(3)))
      BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("abc"))) :+ 1 should ===(
        BinaryIndexedTree(0, 0, 2, 0, Array(3, 4), Array(3, 1)))

      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))) :+ 0 should ===(
        BinaryIndexedTree(0, 0, 3, 0, Array(1, 3, 3), Array(1, 2, 3)))
      BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))) :+ 1 should ===(
        BinaryIndexedTree(0, 0, 4, 0, Array(1, 3, 3, 7), Array(1, 2, 3, 1)))

      val sumTable = Array(1, 3, 3, 10, 5, 11, 7, 36, 9, 19, 1, 22, 3, 7, 5, 76)
      val elemTable = Array(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 1, 2, 3, 4, 5, 6)
      val it16 = BinaryIndexedTree(0, 0, 16, 0, sumTable, elemTable)

      it16 :+ 0 should ===(BinaryIndexedTree(0, 0, 16, 0, sumTable, elemTable))
      it16 :+ 1 should ===(BinaryIndexedTree(0, 0, 17, 0, sumTable :+ 1, elemTable :+ 1))

      it16.slice(9, 69) :+ 0 should ===(BinaryIndexedTree(3, 3, 14, 4, sumTable, elemTable))
      it16.slice(9, 69) :+ 1 should ===(BinaryIndexedTree(0, 0, 13, 0,
        Array(1, 6, 6, 19, 8, 17, 10, 47, 2, 5, 4, 13, 1),
        Array(1, 5, 6, 7, 8, 9, 10, 1, 2, 3, 4, 4, 1)))
    }
    "prepend an element" in {
      0 +: BinaryIndexedTree.empty should ===(BinaryIndexedTree.empty)
      1 +: BinaryIndexedTree.empty should ===(BinaryIndexedTree(0, 0, 1, 0, Array(1), Array(1)))

      0 +: BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("a"))) should ===(
        BinaryIndexedTree(0, 0, 1, 0, Array(1), Array(1)))
      1 +: BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("a"))) should ===(
        BinaryIndexedTree(0, 0, 2, 0, Array(1, 2), Array(1, 1)))

      0 +: BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("abc"))) should ===(
        BinaryIndexedTree(0, 0, 1, 0, Array(3), Array(3)))
      1 +: BinaryIndexedTree(Vector[ByteString1](ByteString1.fromString("abc"))) should ===(
        BinaryIndexedTree(0, 0, 2, 0, Array(1, 4), Array(1, 3)))

      0 +: BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))) should ===(
        BinaryIndexedTree(0, 0, 3, 0, Array(1, 3, 3), Array(1, 2, 3)))
      1 +: BinaryIndexedTree(Vector[ByteString1](
        ByteString1.fromString("a"), ByteString1.fromString("bc"), ByteString1.fromString("def"))) should ===(
        BinaryIndexedTree(0, 0, 4, 0, Array(1, 2, 2, 7), Array(1, 1, 2, 3)))

      val sumTable = Array(1, 3, 3, 10, 5, 11, 7, 36, 9, 19, 1, 22, 3, 7, 5, 76)
      val elemTable = Array(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 1, 2, 3, 4, 5, 6)
      val it16 = BinaryIndexedTree(0, 0, 16, 0, sumTable, elemTable)

      0 +: it16 should ===(BinaryIndexedTree(0, 0, 16, 0, sumTable, elemTable))
      1 +: it16 should ===(BinaryIndexedTree(0, 0, 17, 0,
        Array(1, 2, 2, 7, 4, 9, 6, 29, 8, 17, 10, 28, 2, 5, 4, 71, 6),
        Array(1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 1, 2, 3, 4, 5, 6)))
      0 +: it16.slice(9, 69) should ===(BinaryIndexedTree(3, 3, 14, 4, sumTable, elemTable))
      1 +: it16.slice(9, 69) should ===(BinaryIndexedTree(0, 0, 13, 0,
        Array(1, 2, 5, 13, 7, 15, 9, 47, 1, 3, 3, 10, 4),
        Array(1, 1, 5, 6, 7, 8, 9, 10, 1, 2, 3, 4, 4)))
    }
  }
  "ByteString1" must {
    "drop" in {
      ByteString1.empty.drop(-1) should ===(ByteString(""))
      ByteString1.empty.drop(0) should ===(ByteString(""))
      ByteString1.empty.drop(1) should ===(ByteString(""))
      ByteString1.fromString("a").drop(-1) should ===(ByteString("a"))
      ByteString1.fromString("a").drop(0) should ===(ByteString("a"))
      ByteString1.fromString("a").drop(1) should ===(ByteString(""))
      ByteString1.fromString("a").drop(2) should ===(ByteString(""))
      ByteString1.fromString("abc").drop(-1) should ===(ByteString("abc"))
      ByteString1.fromString("abc").drop(0) should ===(ByteString("abc"))
      ByteString1.fromString("abc").drop(1) should ===(ByteString("bc"))
      ByteString1.fromString("abc").drop(2) should ===(ByteString("c"))
      ByteString1.fromString("abc").drop(3) should ===(ByteString(""))
      ByteString1.fromString("abc").drop(4) should ===(ByteString(""))
      ByteString1.fromString("0123456789").drop(1).take(2) should ===(ByteString("12"))
      ByteString1.fromString("0123456789").drop(5).take(4).drop(1).take(2) should ===(ByteString("67"))
    }
    "dropRight" in {
      ByteString1.empty.dropRight(-1) should ===(ByteString(""))
      ByteString1.empty.dropRight(0) should ===(ByteString(""))
      ByteString1.empty.dropRight(1) should ===(ByteString(""))
      ByteString1.fromString("a").dropRight(-1) should ===(ByteString("a"))
      ByteString1.fromString("a").dropRight(0) should ===(ByteString("a"))
      ByteString1.fromString("a").dropRight(1) should ===(ByteString(""))
      ByteString1.fromString("a").dropRight(2) should ===(ByteString(""))
      ByteString1.fromString("abc").dropRight(-1) should ===(ByteString("abc"))
      ByteString1.fromString("abc").dropRight(0) should ===(ByteString("abc"))
      ByteString1.fromString("abc").dropRight(1) should ===(ByteString("ab"))
      ByteString1.fromString("abc").dropRight(2) should ===(ByteString("a"))
      ByteString1.fromString("abc").dropRight(3) should ===(ByteString(""))
      ByteString1.fromString("abc").dropRight(4) should ===(ByteString(""))
      ByteString1.fromString("0123456789").dropRight(1).take(2) should ===(ByteString("01"))
      ByteString1.fromString("0123456789").dropRight(5).take(4).drop(1).take(2) should ===(ByteString("12"))
    }
    "take" in {
      ByteString1.empty.take(-1) should ===(ByteString(""))
      ByteString1.empty.take(0) should ===(ByteString(""))
      ByteString1.empty.take(1) should ===(ByteString(""))
      ByteString1.fromString("a").take(1) should ===(ByteString("a"))
      ByteString1.fromString("ab").take(-1) should ===(ByteString(""))
      ByteString1.fromString("ab").take(0) should ===(ByteString(""))
      ByteString1.fromString("ab").take(1) should ===(ByteString("a"))
      ByteString1.fromString("ab").take(2) should ===(ByteString("ab"))
      ByteString1.fromString("ab").take(3) should ===(ByteString("ab"))
      ByteString1.fromString("0123456789").take(3).drop(1) should ===(ByteString("12"))
      ByteString1.fromString("0123456789").take(10).take(8).drop(3).take(5) should ===(ByteString("34567"))
    }
  }
  "ByteString1C" must {
    "drop" in {
      ByteString1C.fromString("").drop(-1) should ===(ByteString(""))
      ByteString1C.fromString("").drop(0) should ===(ByteString(""))
      ByteString1C.fromString("").drop(1) should ===(ByteString(""))
      ByteString1C.fromString("a").drop(-1) should ===(ByteString("a"))
      ByteString1C.fromString("a").drop(0) should ===(ByteString("a"))
      ByteString1C.fromString("a").drop(1) should ===(ByteString(""))
      ByteString1C.fromString("a").drop(2) should ===(ByteString(""))
      ByteString1C.fromString("abc").drop(-1) should ===(ByteString("abc"))
      ByteString1C.fromString("abc").drop(0) should ===(ByteString("abc"))
      ByteString1C.fromString("abc").drop(1) should ===(ByteString("bc"))
      ByteString1C.fromString("abc").drop(2) should ===(ByteString("c"))
      ByteString1C.fromString("abc").drop(3) should ===(ByteString(""))
      ByteString1C.fromString("abc").drop(4) should ===(ByteString(""))
      ByteString1C.fromString("0123456789").drop(1).take(2) should ===(ByteString("12"))
      ByteString1C.fromString("0123456789").drop(5).take(4).drop(1).take(2) should ===(ByteString("67"))
    }
    "dropRight" in {
      ByteString1C.fromString("").dropRight(-1) should ===(ByteString(""))
      ByteString1C.fromString("").dropRight(0) should ===(ByteString(""))
      ByteString1C.fromString("").dropRight(1) should ===(ByteString(""))
      ByteString1C.fromString("a").dropRight(-1) should ===(ByteString("a"))
      ByteString1C.fromString("a").dropRight(0) should ===(ByteString("a"))
      ByteString1C.fromString("a").dropRight(1) should ===(ByteString(""))
      ByteString1C.fromString("a").dropRight(2) should ===(ByteString(""))
      ByteString1C.fromString("abc").dropRight(-1) should ===(ByteString("abc"))
      ByteString1C.fromString("abc").dropRight(0) should ===(ByteString("abc"))
      ByteString1C.fromString("abc").dropRight(1) should ===(ByteString("ab"))
      ByteString1C.fromString("abc").dropRight(2) should ===(ByteString("a"))
      ByteString1C.fromString("abc").dropRight(3) should ===(ByteString(""))
      ByteString1C.fromString("abc").dropRight(4) should ===(ByteString(""))
      ByteString1C.fromString("0123456789").dropRight(1).take(2) should ===(ByteString("01"))
      ByteString1C.fromString("0123456789").dropRight(5).take(4).drop(1).take(2) should ===(ByteString("12"))
    }
    "take" in {
      ByteString1.fromString("abcdefg").drop(1).take(0) should ===(ByteString(""))
      ByteString1.fromString("abcdefg").drop(1).take(-1) should ===(ByteString(""))
      ByteString1.fromString("abcdefg").drop(1).take(-2) should ===(ByteString(""))
      ByteString1.fromString("abcdefg").drop(2) should ===(ByteString("cdefg"))
      ByteString1.fromString("abcdefg").drop(2).take(1) should ===(ByteString("c"))
    }
  }
  "ByteStrings" must {
    "i-th elem" in {
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString(""))(0) should ===(97)
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("a"))(0) should ===(97)

      val bss = ByteStrings(Vector(
        ByteString1.fromString("a"),
        ByteString1.fromString("bc"),
        ByteString1.fromString("def"),
        ByteString1.fromString("ghij")))
      bss(0) should ===(97)
      bss(1) should ===(98)
      bss(2) should ===(99)
      bss(3) should ===(100)
      bss(4) should ===(101)
      bss(5) should ===(102)
      bss(6) should ===(103)
      bss(7) should ===(104)
      bss(8) should ===(105)
      bss(9) should ===(106)

      val bss2 = bss.slice(2, 9)
      bss2(0) should ===(99)
      bss2(1) should ===(100)
      bss2(2) should ===(101)
      bss2(3) should ===(102)
      bss2(4) should ===(103)
      bss2(5) should ===(104)
      bss2(6) should ===(105)

      val str = List[Byte](0, 1, 2, 3).mkString
      val numVec = 1024
      val largeBss = ByteStrings(Vector.fill(numVec)(ByteString1.fromString(str)))
      for (i ← 0 until numVec * str.size)
        largeBss(i) should ===(str(i % 4).toByte)
    }
    "drop" in {
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("")).drop(Int.MinValue) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("")).drop(-1) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("")).drop(0) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("")).drop(1) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("")).drop(Int.MaxValue) should ===(ByteString(""))

      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).drop(Int.MinValue) should ===(ByteString("a"))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).drop(-1) should ===(ByteString("a"))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).drop(0) should ===(ByteString("a"))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).drop(1) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).drop(2) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).drop(Int.MaxValue) should ===(ByteString(""))

      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("a")).drop(Int.MinValue) should ===(ByteString("a"))
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("a")).drop(-1) should ===(ByteString("a"))
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("a")).drop(0) should ===(ByteString("a"))
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("a")).drop(1) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("a")).drop(2) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("a")).drop(Int.MaxValue) should ===(ByteString(""))

      val bss = ByteStrings(Vector(
        ByteString1.fromString("a"),
        ByteString1.fromString("bc"),
        ByteString1.fromString("def")))

      bss.drop(Int.MinValue) should ===(ByteString("abcdef"))
      bss.drop(-1) should ===(ByteString("abcdef"))
      bss.drop(0) should ===(ByteString("abcdef"))
      bss.drop(1) should ===(ByteString("bcdef"))
      bss.drop(2) should ===(ByteString("cdef"))
      bss.drop(3) should ===(ByteString("def"))
      bss.drop(4) should ===(ByteString("ef"))
      bss.drop(5) should ===(ByteString("f"))
      bss.drop(6) should ===(ByteString(""))
      bss.drop(7) should ===(ByteString(""))
      bss.drop(Int.MaxValue) should ===(ByteString(""))

      ByteString("0123456789").drop(5).take(2) should ===(ByteString("56"))
      ByteString("0123456789").drop(5).drop(3).take(1) should ===(ByteString("8"))
      (ByteString1C.fromString("a") ++ ByteString1.fromString("bc")).drop(2) should ===(ByteString("c"))
    }
    "dropRight" in {
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("")).dropRight(Int.MinValue) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("")).dropRight(-1) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("")).dropRight(0) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("")).dropRight(1) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("")).dropRight(Int.MaxValue) should ===(ByteString(""))

      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).dropRight(Int.MinValue) should ===(ByteString("a"))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).dropRight(-1) should ===(ByteString("a"))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).dropRight(0) should ===(ByteString("a"))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).dropRight(1) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).dropRight(2) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).dropRight(Int.MaxValue) should ===(ByteString(""))

      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("a")).dropRight(Int.MinValue) should ===(ByteString("a"))
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("a")).dropRight(-1) should ===(ByteString("a"))
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("a")).dropRight(0) should ===(ByteString("a"))
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("a")).dropRight(1) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("a")).dropRight(2) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("a")).dropRight(Int.MaxValue) should ===(ByteString(""))

      val bss = ByteStrings(Vector(
        ByteString1.fromString("a"),
        ByteString1.fromString("bc"),
        ByteString1.fromString("def")))

      bss.dropRight(Int.MinValue) should ===(ByteString("abcdef"))
      bss.dropRight(-1) should ===(ByteString("abcdef"))
      bss.dropRight(0) should ===(ByteString("abcdef"))
      bss.dropRight(1) should ===(ByteString("abcde"))
      bss.dropRight(2) should ===(ByteString("abcd"))
      bss.dropRight(3) should ===(ByteString("abc"))
      bss.dropRight(4) should ===(ByteString("ab"))
      bss.dropRight(5) should ===(ByteString("a"))
      bss.dropRight(6) should ===(ByteString(""))
      bss.dropRight(7) should ===(ByteString(""))
      bss.dropRight(Int.MaxValue) should ===(ByteString(""))

      ByteString("0123456789").dropRight(5).take(2) should ===(ByteString("01"))
      ByteString("0123456789").dropRight(5).drop(3).take(1) should ===(ByteString("3"))
      (ByteString1C.fromString("a") ++ ByteString1.fromString("bc")).dropRight(2) should ===(ByteString("a"))
    }
    "slice" in {
      ByteStrings(ByteString1.fromString(""), ByteString1.fromString("a")).slice(1, 1) should ===(ByteString(""))
      // We explicitly test all edge cases to always test them, refs bug #21237
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).slice(-10, 10) should ===(ByteString("a"))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).slice(-10, 0) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).slice(-10, 1) should ===(ByteString("a"))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).slice(0, 1) should ===(ByteString("a"))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).slice(0, 10) should ===(ByteString("a"))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).slice(1, 10) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).slice(1, -2) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).slice(-10, -100) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).slice(-100, -10) should ===(ByteString(""))
      // Get an empty if `from` is greater then `until`
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).slice(1, 0) should ===(ByteString(""))

      ByteStrings(ByteString1.fromString("ab"), ByteString1.fromString("cd")).slice(2, 2) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString("ab"), ByteString1.fromString("cd")).slice(2, 3) should ===(ByteString("c"))
      ByteStrings(ByteString1.fromString("ab"), ByteString1.fromString("cd")).slice(2, 4) should ===(ByteString("cd"))
      ByteStrings(ByteString1.fromString("ab"), ByteString1.fromString("cd")).slice(3, 4) should ===(ByteString("d"))
      // Can obtain expected results from 6 basic patterns
      ByteStrings(ByteString1.fromString("ab"), ByteString1.fromString("cd")).slice(-10, 10) should ===(ByteString("abcd"))
      ByteStrings(ByteString1.fromString("ab"), ByteString1.fromString("cd")).slice(-10, 0) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString("ab"), ByteString1.fromString("cd")).slice(-10, 4) should ===(ByteString("abcd"))
      ByteStrings(ByteString1.fromString("ab"), ByteString1.fromString("cd")).slice(0, 4) should ===(ByteString("abcd"))
      ByteStrings(ByteString1.fromString("ab"), ByteString1.fromString("cd")).slice(1, -2) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString("ab"), ByteString1.fromString("cd")).slice(0, 10) should ===(ByteString("abcd"))
      ByteStrings(ByteString1.fromString("ab"), ByteString1.fromString("cd")).slice(-10, -100) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString("ab"), ByteString1.fromString("cd")).slice(-100, -10) should ===(ByteString(""))

      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).slice(1, -2) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).slice(-10, -100) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("")).slice(-100, -10) should ===(ByteString(""))

      // various edge cases using raw ByteString1
      ByteString1.fromString("cd").slice(100, 10) should ===(ByteString(""))
      ByteString1.fromString("cd").slice(100, 1000) should ===(ByteString(""))
      ByteString1.fromString("cd").slice(-10, -5) should ===(ByteString(""))
      ByteString1.fromString("cd").slice(-2, -5) should ===(ByteString(""))
      ByteString1.fromString("cd").slice(-2, 1) should ===(ByteString("c"))
      ByteString1.fromString("abcd").slice(1, -1) should ===(ByteString(""))

      // Get an empty if `from` is greater than `until`
      ByteStrings(ByteString1.fromString("ab"), ByteString1.fromString("cd")).slice(4, 0) should ===(ByteString(""))
    }
    "take" in {
      ByteString.empty.take(-1) should ===(ByteString(""))
      ByteString.empty.take(0) should ===(ByteString(""))
      ByteString.empty.take(1) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("bc")).drop(1).take(0) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("bc")).drop(1).take(-1) should ===(ByteString(""))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("bc")).drop(1).take(-2) should ===(ByteString(""))
      (ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("bc")) ++ ByteString1.fromString("defg")).drop(2) should ===(ByteString("cdefg"))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("bc")).drop(2).take(1) should ===(ByteString("c"))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("bc")).take(100) should ===(ByteString("abc"))
      ByteStrings(ByteString1.fromString("a"), ByteString1.fromString("bc")).drop(1).take(100) should ===(ByteString("bc"))
    }
    "indexOf" in {
      ByteString.empty.indexOf(5) should ===(-1)
      val byteString1 = ByteString1.fromString("abc")
      byteString1.indexOf('a') should ===(0)
      byteString1.indexOf('b') should ===(1)
      byteString1.indexOf('c') should ===(2)
      byteString1.indexOf('d') should ===(-1)

      val byteStrings = ByteStrings(ByteString1.fromString("abc"), ByteString1.fromString("efg"))
      byteStrings.indexOf('a') should ===(0)
      byteStrings.indexOf('c') should ===(2)
      byteStrings.indexOf('d') should ===(-1)
      byteStrings.indexOf('e') should ===(3)
      byteStrings.indexOf('f') should ===(4)
      byteStrings.indexOf('g') should ===(5)

      val compact = byteStrings.compact
      compact.indexOf('a') should ===(0)
      compact.indexOf('c') should ===(2)
      compact.indexOf('d') should ===(-1)
      compact.indexOf('e') should ===(3)
      compact.indexOf('f') should ===(4)
      compact.indexOf('g') should ===(5)

    }
    "indexOf from offset" in {
      ByteString.empty.indexOf(5, -1) should ===(-1)
      ByteString.empty.indexOf(5, 0) should ===(-1)
      ByteString.empty.indexOf(5, 1) should ===(-1)
      val byteString1 = ByteString1.fromString("abc")
      byteString1.indexOf('d', -1) should ===(-1)
      byteString1.indexOf('d', 0) should ===(-1)
      byteString1.indexOf('d', 1) should ===(-1)
      byteString1.indexOf('d', 4) should ===(-1)
      byteString1.indexOf('a', -1) should ===(0)
      byteString1.indexOf('a', 0) should ===(0)
      byteString1.indexOf('a', 1) should ===(-1)

      val byteStrings = ByteStrings(ByteString1.fromString("abc"), ByteString1.fromString("efg"))
      byteStrings.indexOf('c', -1) should ===(2)
      byteStrings.indexOf('c', 0) should ===(2)
      byteStrings.indexOf('c', 2) should ===(2)
      byteStrings.indexOf('c', 3) should ===(-1)

      byteStrings.indexOf('e', -1) should ===(3)
      byteStrings.indexOf('e', 0) should ===(3)
      byteStrings.indexOf('e', 1) should ===(3)
      byteStrings.indexOf('e', 4) should ===(-1)
      byteStrings.indexOf('e', 6) should ===(-1)

      byteStrings.indexOf('g', -1) should ===(5)
      byteStrings.indexOf('g', 0) should ===(5)
      byteStrings.indexOf('g', 1) should ===(5)
      byteStrings.indexOf('g', 4) should ===(5)
      byteStrings.indexOf('g', 5) should ===(5)
      byteStrings.indexOf('g', 6) should ===(-1)

      val compact = byteStrings.compact
      compact.indexOf('c', -1) should ===(2)
      compact.indexOf('c', 0) should ===(2)
      compact.indexOf('c', 2) should ===(2)
      compact.indexOf('c', 3) should ===(-1)

      compact.indexOf('e', -1) should ===(3)
      compact.indexOf('e', 0) should ===(3)
      compact.indexOf('e', 1) should ===(3)
      compact.indexOf('e', 4) should ===(-1)
      compact.indexOf('e', 6) should ===(-1)

      compact.indexOf('g', -1) should ===(5)
      compact.indexOf('g', 0) should ===(5)
      compact.indexOf('g', 1) should ===(5)
      compact.indexOf('g', 4) should ===(5)
      compact.indexOf('g', 5) should ===(5)
      compact.indexOf('g', 6) should ===(-1)
    }
    "++" in {
      ByteString.empty ++ ByteString.empty should ===(ByteString(""))
      ByteString.empty ++ ByteString("a") should ===(ByteString("a"))
      ByteString("a") ++ ByteString.empty should ===(ByteString("a"))
      ByteString("a") ++ ByteString("b") should ===(ByteString("ab"))

      ByteString.empty ++ ByteString.empty === (ByteString(""))
      ByteString("a") ++ ByteString.empty should ===(ByteString("a"))
      ByteString.empty ++ ByteString("a") ++ ByteString.empty should ===(ByteString("a"))
      ByteString("a") ++ ByteString("b") should ===(ByteString("ab"))

      val bss = ByteStrings(Vector(
        ByteString1.fromString("a"),
        ByteString1.fromString("bc"),
        ByteString1.fromString("def"),
        ByteString1.fromString("ghij"),
        ByteString1.fromString("klmno"),
        ByteString1.fromString("pqrstu"),
        ByteString1.fromString("vwxyzAB"),
        ByteString1.fromString("CDEFGHIJ"),
        ByteString1.fromString("KLMNOPQRS"),
        ByteString1.fromString("1234567890"),
        ByteString1.fromString("a"),
        ByteString1.fromString("bc"),
        ByteString1.fromString("def"),
        ByteString1.fromString("ghij"),
        ByteString1.fromString("klmno"),
        ByteString1.fromString("pqrstu")))
      val str = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRS1234567890abcdefghijklmnopqrstu"

      val slice1_76 = bss.slice(1, 76)
      val strSlice1_76 = str.slice(1, 76)
      val slice2_71 = bss.slice(2, 71)
      val strSlice2_71 = str.slice(2, 71)
      val slice3_70 = bss.slice(3, 70)
      val strSlice3_70 = str.slice(3, 70)
      slice1_76 ++ ByteString.empty === (ByteString(strSlice1_76))
      slice1_76 ++ slice1_76 should ===(ByteString(strSlice1_76 + strSlice1_76))
      slice1_76 ++ slice2_71 should ===(ByteString(strSlice1_76 + strSlice2_71))
      slice1_76 ++ slice3_70 should ===(ByteString(strSlice1_76 + strSlice3_70))
      slice2_71 ++ slice1_76 should ===(ByteString(strSlice2_71 + strSlice1_76))
      slice2_71 ++ slice2_71 should ===(ByteString(strSlice2_71 + strSlice2_71))
      slice2_71 ++ slice3_70 should ===(ByteString(strSlice2_71 + strSlice3_70))
      slice3_70 ++ slice1_76 should ===(ByteString(strSlice3_70 + strSlice1_76))
      slice3_70 ++ slice2_71 should ===(ByteString(strSlice3_70 + strSlice2_71))
      slice3_70 ++ slice3_70 should ===(ByteString(strSlice3_70 + strSlice3_70))

      val numTrial = 100
      val minIdx = 0
      val maxIdx = str.size - 1
      for (_ ← 0 until numTrial) {
        def range(): (Int, Int) = {
          val start = Random.nextInt(maxIdx)
          val last = Random.nextInt(maxIdx - start) + start
          (start, last)
        }
        val first = range()
        val second = range()
        val last = range()
        bss.slice(first._1, first._2 + 1) ++ bss.slice(second._1, second._2 + 1) ++ bss.slice(last._1, last._2 + 1) should ===(
          ByteString(str.slice(first._1, first._2 + 1) ++ str.slice(second._1, second._2 + 1) ++ str.slice(last._1, last._2 + 1))
        )
      }
    }
  }

  "A ByteString" must {
    "have correct size" when {
      "concatenating" in { check((a: ByteString, b: ByteString) ⇒ (a ++ b).size == a.size + b.size) }
      "dropping" in { check((a: ByteString, b: ByteString) ⇒ (a ++ b).drop(b.size).size == a.size) }
      "taking" in { check((a: ByteString, b: ByteString) ⇒ (a ++ b).take(a.size) == a) }
      "takingRight" in { check((a: ByteString, b: ByteString) ⇒ (a ++ b).takeRight(b.size) == b) }
      "droppnig then taking" in { check((a: ByteString, b: ByteString) ⇒ (b ++ a ++ b).drop(b.size).take(a.size) == a) }
      "droppingRight" in { check((a: ByteString, b: ByteString) ⇒ (b ++ a ++ b).drop(b.size).dropRight(b.size) == a) }
    }

    "be sequential" when {
      "taking" in { check((a: ByteString, b: ByteString) ⇒ (a ++ b).take(a.size) == a) }
      "dropping" in { check((a: ByteString, b: ByteString) ⇒ (a ++ b).drop(a.size) == b) }
    }

    "be equal to the original" when {
      "compacting" in { check { xs: ByteString ⇒ val ys = xs.compact; (xs == ys) && ys.isCompact } }
      "recombining" in {
        check { (xs: ByteString, from: Int, until: Int) ⇒
          val (tmp, c) = xs.splitAt(until)
          val (a, b) = tmp.splitAt(from)
          (a ++ b ++ c) == xs
        }
      }
      def excerciseRecombining(xs: ByteString, from: Int, until: Int) = {
        val (tmp, c) = xs.splitAt(until)
        val (a, b) = tmp.splitAt(from)
        (a ++ b ++ c) should ===(xs)
      }
      "recombining - edge cases" in {
        excerciseRecombining(ByteStrings(Vector(ByteString1(Array[Byte](1)), ByteString1(Array[Byte](2)))), -2147483648, 112121212)
        excerciseRecombining(ByteStrings(Vector(ByteString1(Array[Byte](100)))), 0, 2)
        excerciseRecombining(ByteStrings(Vector(ByteString1(Array[Byte](100)))), -2147483648, 2)
        excerciseRecombining(ByteStrings(Vector(ByteString1.fromString("ab"), ByteString1.fromString("cd"))), 0, 1)
        excerciseRecombining(ByteString1.fromString("abc").drop(1).take(1), -324234, 234232)
        excerciseRecombining(ByteString("a"), 0, 2147483647)
        excerciseRecombining(ByteStrings(Vector(ByteString1.fromString("ab"), ByteString1.fromString("cd"))).drop(2), 2147483647, 1)
        excerciseRecombining(ByteString1.fromString("ab").drop1(1), Int.MaxValue, Int.MaxValue)
      }
    }

    "behave as expected" when {
      "created from and decoding to String" in { check { s: String ⇒ ByteString(s, "UTF-8").decodeString("UTF-8") == s } }

      "compacting" in {
        check { a: ByteString ⇒
          val wasCompact = a.isCompact
          val b = a.compact
          ((!wasCompact) || (b eq a)) &&
            (b == a) &&
            b.isCompact &&
            (b.compact eq b)
        }
      }

      "asByteBuffers" in {
        check { (a: ByteString) ⇒ if (a.isCompact) a.asByteBuffers.size == 1 && a.asByteBuffers.head == a.asByteBuffer else a.asByteBuffers.size > 0 }
        check { (a: ByteString) ⇒ a.asByteBuffers.foldLeft(ByteString.empty) { (bs, bb) ⇒ bs ++ ByteString(bb) } == a }
        check { (a: ByteString) ⇒ a.asByteBuffers.forall(_.isReadOnly) }
        check { (a: ByteString) ⇒
          import scala.collection.JavaConverters.iterableAsScalaIterableConverter
          a.asByteBuffers.zip(a.getByteBuffers().asScala).forall(x ⇒ x._1 == x._2)
        }
      }

      "toString should start with ByteString(" in {
        check { (bs: ByteString) ⇒
          bs.toString.startsWith("ByteString(")
        }
      }
    }
    "behave like a Vector" when {
      "concatenating" in { check { (a: ByteString, b: ByteString) ⇒ likeVectors(a, b) { _ ++ _ } } }

      "calling apply" in {
        check { slice: ByteStringSlice ⇒
          slice match {
            case (xs, i1, i2) ⇒ likeVector(xs) { seq ⇒
              (if ((i1 >= 0) && (i1 < seq.length)) seq(i1) else 0,
                if ((i2 >= 0) && (i2 < seq.length)) seq(i2) else 0)
            }
          }
        }
      }

      "calling head" in { check { a: ByteString ⇒ a.isEmpty || likeVector(a) { _.head } } }
      "calling tail" in { check { a: ByteString ⇒ a.isEmpty || likeVector(a) { _.tail } } }
      "calling last" in { check { a: ByteString ⇒ a.isEmpty || likeVector(a) { _.last } } }
      "calling init" in { check { a: ByteString ⇒ a.isEmpty || likeVector(a) { _.init } } }
      "calling length" in { check { a: ByteString ⇒ likeVector(a) { _.length } } }

      "calling span" in { check { (a: ByteString, b: Byte) ⇒ likeVector(a)({ _.span(_ != b) match { case (a, b) ⇒ (a, b) } }) } }

      "calling takeWhile" in { check { (a: ByteString, b: Byte) ⇒ likeVector(a)({ _.takeWhile(_ != b) }) } }
      "calling dropWhile" in { check { (a: ByteString, b: Byte) ⇒ likeVector(a) { _.dropWhile(_ != b) } } }
      "calling indexWhere" in { check { (a: ByteString, b: Byte) ⇒ likeVector(a) { _.indexWhere(_ == b) } } }
      "calling indexOf" in { check { (a: ByteString, b: Byte) ⇒ likeVector(a) { _.indexOf(b) } } }
      // this actually behave weird for Vector and negative indexes - SI9936, fixed in Scala 2.12
      // so let's just skip negative indexes (doesn't make much sense anyway)
      "calling indexOf(elem, idx)" in { check { (a: ByteString, b: Byte, idx: Int) ⇒ likeVector(a) { _.indexOf(b, math.max(0, idx)) } } }

      "calling foreach" in { check { a: ByteString ⇒ likeVector(a) { it ⇒ var acc = 0; it foreach { acc += _ }; acc } } }
      "calling foldLeft" in { check { a: ByteString ⇒ likeVector(a) { _.foldLeft(0) { _ + _ } } } }
      "calling toArray" in { check { a: ByteString ⇒ likeVector(a) { _.toArray.toSeq } } }

      "calling slice" in {
        check { slice: ByteStringSlice ⇒
          slice match {
            case (xs, from, until) ⇒ likeVector(xs)({
              _.slice(from, until)
            })
          }
        }
      }

      "calling take and drop" in {
        check { slice: ByteStringSlice ⇒
          slice match {
            case (xs, from, until) ⇒
              likeVector(xs)({
                _.take(math.max(0, from) + math.abs(until)).drop(from)
              })
          }
        }
      }

      "calling drop and take" in {
        check { slice: ByteStringSlice ⇒
          slice match {
            case (xs, from, until) ⇒
              likeVector(xs)({
                _.drop(from).take(until - from)
              })
          }
        }
      }

      "calling dropRight and drop" in {
        check { slice: ByteStringSlice ⇒
          slice match {
            case (xs, from, until) ⇒
              likeVector(xs)({
                _.dropRight(math.min(xs.length, xs.length - from) + math.abs(until)).drop(from)
              })
          }
        }
      }

      "calling takeRight and take" in {
        check { slice: ByteStringSlice ⇒
          slice match {
            case (xs, from, until) ⇒
              likeVector(xs)({
                _.takeRight(xs.length - from).take(until - from)
              })
          }
        }
      }

      "calling copyToArray" in {
        check { slice: ByteStringSlice ⇒
          slice match {
            case (xs, from, until) ⇒ likeVector(xs)({ it ⇒
              val array = Array.ofDim[Byte](xs.length)
              it.slice(from, until).copyToArray(array, from, until)
              array.toSeq
            })
          }
        }
      }
    }

    "serialize correctly" when {
      "parsing regular ByteString1C as compat" in {
        val oldSerd = "aced000573720021616b6b612e7574696c2e42797465537472696e672442797465537472696e67314336e9eed0afcfe4a40200015b000562797465737400025b427872001b616b6b612e7574696c2e436f6d7061637442797465537472696e67fa2925150f93468f0200007870757200025b42acf317f8060854e002000078700000000a74657374737472696e67"
        val bs = ByteString("teststring", "UTF8")
        val str = hexFromSer(bs)

        require(oldSerd == str)
      }

      "given all types of ByteString" in {
        check { bs: ByteString ⇒
          testSer(bs)
        }
      }

      "with a large concatenated bytestring" in {
        // coverage for #20901
        val original = ByteString(Array.fill[Byte](1000)(1)) ++ ByteString(Array.fill[Byte](1000)(2))

        deserialize(serialize(original)) shouldEqual original
      }
    }
  }

  "A ByteStringIterator" must {
    "behave like a buffered Vector Iterator" when {
      "concatenating" in { check { (a: ByteString, b: ByteString) ⇒ likeVecIts(a, b) { (a, b) ⇒ (a ++ b).toSeq } } }

      "calling head" in { check { a: ByteString ⇒ a.isEmpty || likeVecIt(a) { _.head } } }
      "calling next" in { check { a: ByteString ⇒ a.isEmpty || likeVecIt(a) { _.next() } } }
      "calling hasNext" in { check { a: ByteString ⇒ likeVecIt(a) { _.hasNext } } }
      "calling length" in { check { a: ByteString ⇒ likeVecIt(a) { _.length } } }
      "calling duplicate" in { check { a: ByteString ⇒ likeVecIt(a)({ _.duplicate match { case (a, b) ⇒ (a.toSeq, b.toSeq) } }, strict = false) } }

      // Have to used toList instead of toSeq here, iterator.span (new in
      // Scala-2.9) seems to be broken in combination with toSeq for the
      // scala.collection default Iterator (see Scala issue SI-5838).
      "calling span" in { check { (a: ByteString, b: Byte) ⇒ likeVecIt(a)({ _.span(_ != b) match { case (a, b) ⇒ (a.toList, b.toList) } }, strict = false) } }

      "calling takeWhile" in { check { (a: ByteString, b: Byte) ⇒ likeVecIt(a)({ _.takeWhile(_ != b).toSeq }, strict = false) } }
      "calling dropWhile" in { check { (a: ByteString, b: Byte) ⇒ likeVecIt(a) { _.dropWhile(_ != b).toSeq } } }
      "calling indexWhere" in { check { (a: ByteString, b: Byte) ⇒ likeVecIt(a) { _.indexWhere(_ == b) } } }
      "calling indexOf" in { check { (a: ByteString, b: Byte) ⇒ likeVecIt(a) { _.indexOf(b) } } }
      "calling toSeq" in { check { a: ByteString ⇒ likeVecIt(a) { _.toSeq } } }
      "calling foreach" in { check { a: ByteString ⇒ likeVecIt(a) { it ⇒ var acc = 0; it foreach { acc += _ }; acc } } }
      "calling foldLeft" in { check { a: ByteString ⇒ likeVecIt(a) { _.foldLeft(0) { _ + _ } } } }
      "calling toArray" in { check { a: ByteString ⇒ likeVecIt(a) { _.toArray.toSeq } } }

      "calling slice" in {
        check { slice: ByteStringSlice ⇒
          slice match {
            case (xs, from, until) ⇒ likeVecIt(xs)({
              _.slice(from, until).toSeq
            }, strict = false)
          }
        }
      }

      "calling take and drop" in {
        check { slice: ByteStringSlice ⇒
          slice match {
            case (xs, from, until) ⇒ likeVecIt(xs)({
              _.drop(from).take(until - from).toSeq
            }, strict = false)
          }
        }
      }

      "calling copyToArray" in {
        check { slice: ByteStringSlice ⇒
          slice match {
            case (xs, from, until) ⇒ likeVecIt(xs)({ it ⇒
              val array = Array.ofDim[Byte](xs.length)
              it.slice(from, until).copyToArray(array, from, until)
              array.toSeq
            }, strict = false)
          }
        }
      }
    }

    "function as expected" when {
      "getting Bytes, using getByte and getBytes" in {
        // mixing getByte and getBytes here for more rigorous testing
        check { slice: ByteStringSlice ⇒
          val (bytes, from, to) = slice
          val input = bytes.iterator
          val output = Array.ofDim[Byte](bytes.length)
          for (i ← 0 until from) output(i) = input.getByte
          input.getBytes(output, from, to - from)
          for (i ← to until bytes.length) output(i) = input.getByte
          (output.toSeq == bytes) && (input.isEmpty)
        }
      }

      "getting Bytes with a given length" in {
        check {
          slice: ByteStringSlice ⇒
            val (bytes, _, _) = slice
            val input = bytes.iterator
            (input.getBytes(bytes.length).toSeq == bytes) && input.isEmpty
        }
      }

      "getting ByteString with a given length" in {
        check {
          slice: ByteStringSlice ⇒
            val (bytes, _, _) = slice
            val input = bytes.iterator
            (input.getByteString(bytes.length) == bytes) && input.isEmpty
        }
      }

      "getting Bytes, using the InputStream wrapper" in {
        // combining skip and both read methods here for more rigorous testing
        check { slice: ByteStringSlice ⇒
          val (bytes, from, to) = slice
          val a = (0 max from) min bytes.length
          val b = (a max to) min bytes.length
          val input = bytes.iterator
          val output = Array.ofDim[Byte](bytes.length)

          input.asInputStream.skip(a)

          val toRead = b - a
          var (nRead, eof) = (0, false)
          while ((nRead < toRead) && !eof) {
            val n = input.asInputStream.read(output, a + nRead, toRead - nRead)
            if (n == -1) eof = true
            else nRead += n
          }
          if (eof) throw new RuntimeException("Unexpected EOF")

          for (i ← b until bytes.length) output(i) = input.asInputStream.read().toByte

          (output.toSeq.drop(a) == bytes.drop(a)) &&
            (input.asInputStream.read() == -1) &&
            ((output.length < 1) || (input.asInputStream.read(output, 0, 1) == -1))
        }
      }

      "calling copyToBuffer" in {
        check { bytes: ByteString ⇒
          import java.nio.ByteBuffer
          val buffer = ByteBuffer.allocate(bytes.size)
          bytes.copyToBuffer(buffer)
          buffer.flip()
          val array = Array.ofDim[Byte](bytes.size)
          buffer.get(array)
          bytes == array.toSeq
        }
      }
    }

    "decode data correctly" when {
      "decoding Short in big-endian" in { check { slice: ByteStringSlice ⇒ testShortDecoding(slice, BIG_ENDIAN) } }
      "decoding Short in little-endian" in { check { slice: ByteStringSlice ⇒ testShortDecoding(slice, LITTLE_ENDIAN) } }
      "decoding Int in big-endian" in { check { slice: ByteStringSlice ⇒ testIntDecoding(slice, BIG_ENDIAN) } }
      "decoding Int in little-endian" in { check { slice: ByteStringSlice ⇒ testIntDecoding(slice, LITTLE_ENDIAN) } }
      "decoding Long in big-endian" in { check { slice: ByteStringSlice ⇒ testLongDecoding(slice, BIG_ENDIAN) } }
      "decoding Long in little-endian" in { check { slice: ByteStringSlice ⇒ testLongDecoding(slice, LITTLE_ENDIAN) } }
      "decoding Float in big-endian" in { check { slice: ByteStringSlice ⇒ testFloatDecoding(slice, BIG_ENDIAN) } }
      "decoding Float in little-endian" in { check { slice: ByteStringSlice ⇒ testFloatDecoding(slice, LITTLE_ENDIAN) } }
      "decoding Double in big-endian" in { check { slice: ByteStringSlice ⇒ testDoubleDecoding(slice, BIG_ENDIAN) } }
      "decoding Double in little-endian" in { check { slice: ByteStringSlice ⇒ testDoubleDecoding(slice, LITTLE_ENDIAN) } }
    }
  }

  "A ByteStringBuilder" must {
    "function like a VectorBuilder" when {
      "adding various contents using ++= and +=" in {
        check { (array1: Array[Byte], array2: Array[Byte], bs1: ByteString, bs2: ByteString, bs3: ByteString) ⇒
          likeVecBld { builder ⇒
            builder ++= array1
            bs1 foreach { b ⇒ builder += b }
            builder ++= bs2
            bs3 foreach { b ⇒ builder += b }
            builder ++= Vector(array2: _*)
          }
        }
      }
    }
    "function as expected" when {
      "putting Bytes, using putByte and putBytes" in {
        // mixing putByte and putBytes here for more rigorous testing
        check { slice: ArraySlice[Byte] ⇒
          val (data, from, to) = slice
          val builder = ByteString.newBuilder
          for (i ← 0 until from) builder.putByte(data(i))
          builder.putBytes(data, from, to - from)
          for (i ← to until data.length) builder.putByte(data(i))
          data.toSeq == builder.result
        }
      }

      "putting Bytes, using the OutputStream wrapper" in {
        // mixing the write methods here for more rigorous testing
        check { slice: ArraySlice[Byte] ⇒
          val (data, from, to) = slice
          val builder = ByteString.newBuilder
          for (i ← 0 until from) builder.asOutputStream.write(data(i).toInt)
          builder.asOutputStream.write(data, from, to - from)
          for (i ← to until data.length) builder.asOutputStream.write(data(i).toInt)
          data.toSeq == builder.result
        }
      }
    }

    "encode data correctly" when {
      "encoding Short in big-endian" in { check { slice: ArraySlice[Short] ⇒ testShortEncoding(slice, BIG_ENDIAN) } }
      "encoding Short in little-endian" in { check { slice: ArraySlice[Short] ⇒ testShortEncoding(slice, LITTLE_ENDIAN) } }
      "encoding Int in big-endian" in { check { slice: ArraySlice[Int] ⇒ testIntEncoding(slice, BIG_ENDIAN) } }
      "encoding Int in little-endian" in { check { slice: ArraySlice[Int] ⇒ testIntEncoding(slice, LITTLE_ENDIAN) } }
      "encoding Long in big-endian" in { check { slice: ArraySlice[Long] ⇒ testLongEncoding(slice, BIG_ENDIAN) } }
      "encoding Long in little-endian" in { check { slice: ArraySlice[Long] ⇒ testLongEncoding(slice, LITTLE_ENDIAN) } }
      "encoding LongPart in big-endian" in { check { slice: ArrayNumBytes[Long] ⇒ testLongPartEncoding(slice, BIG_ENDIAN) } }
      "encoding LongPart in little-endian" in { check { slice: ArrayNumBytes[Long] ⇒ testLongPartEncoding(slice, LITTLE_ENDIAN) } }
      "encoding Float in big-endian" in { check { slice: ArraySlice[Float] ⇒ testFloatEncoding(slice, BIG_ENDIAN) } }
      "encoding Float in little-endian" in { check { slice: ArraySlice[Float] ⇒ testFloatEncoding(slice, LITTLE_ENDIAN) } }
      "encoding Double in big-endian" in { check { slice: ArraySlice[Double] ⇒ testDoubleEncoding(slice, BIG_ENDIAN) } }
      "encoding Double in little-endian" in { check { slice: ArraySlice[Double] ⇒ testDoubleEncoding(slice, LITTLE_ENDIAN) } }
    }

    "have correct empty info" when {
      "is empty" in {
        check { a: ByteStringBuilder ⇒ a.isEmpty }
      }
      "is nonEmpty" in {
        check { a: ByteStringBuilder ⇒
          a.putByte(1.toByte)
          a.nonEmpty
        }
      }
    }
  }
}
