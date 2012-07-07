package akka.util

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.prop.Checkers
import org.scalacheck._
import org.scalacheck.Arbitrary._
import org.scalacheck.Prop._
import org.scalacheck.Gen._

import scala.collection.mutable.Builder

import java.nio.{ ByteBuffer, ShortBuffer, IntBuffer, FloatBuffer, DoubleBuffer }
import java.nio.ByteOrder, ByteOrder.{ BIG_ENDIAN, LITTLE_ENDIAN }
import java.lang.Float.floatToRawIntBits
import java.lang.Double.doubleToRawLongBits

class ByteStringSpec extends WordSpec with MustMatchers with Checkers {

  def genSimpleByteString(min: Int, max: Int) = for {
    n ← choose(min, max)
    b ← Gen.containerOfN[Array, Byte](n, arbitrary[Byte])
    from ← choose(0, b.length)
    until ← choose(from, b.length)
  } yield ByteString(b).slice(from, until)

  implicit val arbitraryByteString: Arbitrary[ByteString] = Arbitrary {
    Gen.sized { s ⇒
      for {
        chunks ← choose(0, s)
        bytes ← listOfN(chunks, genSimpleByteString(1, s / (chunks max 1)))
      } yield (ByteString.empty /: bytes)(_ ++ _)
    }
  }

  type ByteStringSlice = (ByteString, Int, Int)

  implicit val arbitraryByteStringSlice: Arbitrary[ByteStringSlice] = Arbitrary {
    for {
      xs ← arbitraryByteString.arbitrary
      from ← choose(0, xs.length)
      until ← choose(from, xs.length)
    } yield (xs, from, until)
  }

  type ArraySlice[A] = (Array[A], Int, Int)

  def arbSlice[A](arbArray: Arbitrary[Array[A]]): Arbitrary[ArraySlice[A]] = Arbitrary {
    for {
      xs ← arbArray.arbitrary
      from ← choose(0, xs.length)
      until ← choose(from, xs.length)
    } yield (xs, from, until)
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
      (!strict || (bsAIt.toSeq, bsBIt.toSeq) == (vecAIt.toSeq, vecBIt.toSeq))
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
    for (i ← 0 to a - 1) decoded(i) = input.getShort(byteOrder)
    input.getShorts(decoded, a, b - a)(byteOrder)
    for (i ← b to n - 1) decoded(i) = input.getShort(byteOrder)
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
    for (i ← 0 to a - 1) decoded(i) = input.getInt(byteOrder)
    input.getInts(decoded, a, b - a)(byteOrder)
    for (i ← b to n - 1) decoded(i) = input.getInt(byteOrder)
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
    for (i ← 0 to a - 1) decoded(i) = input.getLong(byteOrder)
    input.getLongs(decoded, a, b - a)(byteOrder)
    for (i ← b to n - 1) decoded(i) = input.getLong(byteOrder)
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
    for (i ← 0 to a - 1) decoded(i) = input.getFloat(byteOrder)
    input.getFloats(decoded, a, b - a)(byteOrder)
    for (i ← b to n - 1) decoded(i) = input.getFloat(byteOrder)
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
    for (i ← 0 to a - 1) decoded(i) = input.getDouble(byteOrder)
    input.getDoubles(decoded, a, b - a)(byteOrder)
    for (i ← b to n - 1) decoded(i) = input.getDouble(byteOrder)
    ((decoded.toSeq map doubleToRawLongBits) == (reference.toSeq map doubleToRawLongBits)) &&
      (input.toSeq == bytes.drop(n * elemSize))
  }

  def testShortEncoding(slice: ArraySlice[Short], byteOrder: ByteOrder): Boolean = {
    val elemSize = 2
    val (data, from, until) = slice
    val reference = Array.ofDim[Byte](data.length * elemSize)
    ByteBuffer.wrap(reference).order(byteOrder).asShortBuffer.put(data)
    val builder = ByteString.newBuilder
    for (i ← 0 to from - 1) builder.putShort(data(i))(byteOrder)
    builder.putShorts(data, from, until - from)(byteOrder)
    for (i ← until to data.length - 1) builder.putShort(data(i))(byteOrder)
    reference.toSeq == builder.result
  }

  def testIntEncoding(slice: ArraySlice[Int], byteOrder: ByteOrder): Boolean = {
    val elemSize = 4
    val (data, from, until) = slice
    val reference = Array.ofDim[Byte](data.length * elemSize)
    ByteBuffer.wrap(reference).order(byteOrder).asIntBuffer.put(data)
    val builder = ByteString.newBuilder
    for (i ← 0 to from - 1) builder.putInt(data(i))(byteOrder)
    builder.putInts(data, from, until - from)(byteOrder)
    for (i ← until to data.length - 1) builder.putInt(data(i))(byteOrder)
    reference.toSeq == builder.result
  }

  def testLongEncoding(slice: ArraySlice[Long], byteOrder: ByteOrder): Boolean = {
    val elemSize = 8
    val (data, from, until) = slice
    val reference = Array.ofDim[Byte](data.length * elemSize)
    ByteBuffer.wrap(reference).order(byteOrder).asLongBuffer.put(data)
    val builder = ByteString.newBuilder
    for (i ← 0 to from - 1) builder.putLong(data(i))(byteOrder)
    builder.putLongs(data, from, until - from)(byteOrder)
    for (i ← until to data.length - 1) builder.putLong(data(i))(byteOrder)
    reference.toSeq == builder.result
  }

  def testFloatEncoding(slice: ArraySlice[Float], byteOrder: ByteOrder): Boolean = {
    val elemSize = 4
    val (data, from, until) = slice
    val reference = Array.ofDim[Byte](data.length * elemSize)
    ByteBuffer.wrap(reference).order(byteOrder).asFloatBuffer.put(data)
    val builder = ByteString.newBuilder
    for (i ← 0 to from - 1) builder.putFloat(data(i))(byteOrder)
    builder.putFloats(data, from, until - from)(byteOrder)
    for (i ← until to data.length - 1) builder.putFloat(data(i))(byteOrder)
    reference.toSeq == builder.result
  }

  def testDoubleEncoding(slice: ArraySlice[Double], byteOrder: ByteOrder): Boolean = {
    val elemSize = 8
    val (data, from, until) = slice
    val reference = Array.ofDim[Byte](data.length * elemSize)
    ByteBuffer.wrap(reference).order(byteOrder).asDoubleBuffer.put(data)
    val builder = ByteString.newBuilder
    for (i ← 0 to from - 1) builder.putDouble(data(i))(byteOrder)
    builder.putDoubles(data, from, until - from)(byteOrder)
    for (i ← until to data.length - 1) builder.putDouble(data(i))(byteOrder)
    reference.toSeq == builder.result
  }

  "A ByteString" must {
    "have correct size" when {
      "concatenating" in { check((a: ByteString, b: ByteString) ⇒ (a ++ b).size == a.size + b.size) }
      "dropping" in { check((a: ByteString, b: ByteString) ⇒ (a ++ b).drop(b.size).size == a.size) }
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
    }
    "behave like a Vector" when {
      "concatenating" in { check { (a: ByteString, b: ByteString) ⇒ likeVectors(a, b) { (a, b) ⇒ (a ++ b) } } }

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
            case (xs, from, until) ⇒ likeVector(xs)({
              _.drop(from).take(until - from)
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
          val (bytes, from, until) = slice
          val input = bytes.iterator
          val output = Array.ofDim[Byte](bytes.length)
          for (i ← 0 to from - 1) output(i) = input.getByte
          input.getBytes(output, from, until - from)
          for (i ← until to bytes.length - 1) output(i) = input.getByte
          (output.toSeq == bytes) && (input.isEmpty)
        }
      }

      "getting Bytes, using the InputStream wrapper" in {
        // combining skip and both read methods here for more rigorous testing
        check { slice: ByteStringSlice ⇒
          val (bytes, from, until) = slice
          val a = (0 max from) min bytes.length
          val b = (a max until) min bytes.length
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

          for (i ← b to bytes.length - 1) output(i) = input.asInputStream.read().toByte

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
          val (data, from, until) = slice
          val builder = ByteString.newBuilder
          for (i ← 0 to from - 1) builder.putByte(data(i))
          builder.putBytes(data, from, until - from)
          for (i ← until to data.length - 1) builder.putByte(data(i))
          data.toSeq == builder.result
        }
      }

      "putting Bytes, using the OutputStream wrapper" in {
        // mixing the write methods here for more rigorous testing
        check { slice: ArraySlice[Byte] ⇒
          val (data, from, until) = slice
          val builder = ByteString.newBuilder
          for (i ← 0 to from - 1) builder.asOutputStream.write(data(i).toInt)
          builder.asOutputStream.write(data, from, until - from)
          for (i ← until to data.length - 1) builder.asOutputStream.write(data(i).toInt)
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
      "encoding Float in big-endian" in { check { slice: ArraySlice[Float] ⇒ testFloatEncoding(slice, BIG_ENDIAN) } }
      "encoding Float in little-endian" in { check { slice: ArraySlice[Float] ⇒ testFloatEncoding(slice, LITTLE_ENDIAN) } }
      "encoding Double in big-endian" in { check { slice: ArraySlice[Double] ⇒ testDoubleEncoding(slice, BIG_ENDIAN) } }
      "encoding Double in little-endian" in { check { slice: ArraySlice[Double] ⇒ testDoubleEncoding(slice, LITTLE_ENDIAN) } }
    }
  }
}
