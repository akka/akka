/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import akka.stream.testkit.AkkaSpec
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.collection.immutable
import akka.stream.impl.ActorBasedFlowMaterializer
import akka.stream.MaterializerSettings
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.stream.io.Codecs._
import akka.stream.io.Decoder.DecodeStep

class CodecSpec extends AkkaSpec {

  val materializer = new ActorBasedFlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2), system)

  def chunkUp(data: ByteString): (ByteString, immutable.Seq[ByteString]) = {
    if (data.isEmpty) {
      if (ThreadLocalRandom.current().nextBoolean()) (ByteString.empty, List(ByteString.empty))
      else (data, Nil)
    } else {
      if (ThreadLocalRandom.current().nextBoolean()) (ByteString.empty, List(data))
      else {
        val splitIdx = ThreadLocalRandom.current().nextInt(data.size)
        (data.drop(splitIdx), List(data.slice(0, splitIdx)))
      }
    }
  }

  def rechunk(flow: Flow[ByteString]): Flow[ByteString] = {
    flow.transform(ByteString.empty)(
      f = (aggregated, incoming) ⇒ chunkUp(aggregated ++ incoming),
      onComplete = (aggregated) ⇒ List(aggregated))

  }

  def decodingTransform[Out](flow: Flow[ByteString], decoder: Decoder[Out]): Flow[Out] =
    flow.transform(decoder)(
      f = (builder, fragment) ⇒ (decoder, decoder.parseFragment(fragment)))

  abstract class TestSetup[T] {
    def testSequence: immutable.Seq[T]
    def encode(in: T): ByteString
    def decoder: Decoder[T]

    val decoded = decodingTransform(rechunk(Flow(testSequence) map encode), decoder)
      .fold(Vector.empty[T])((acc, in) ⇒ acc :+ in).toFuture(materializer)

    Await.result(decoded, 3.seconds) should be(testSequence)
  }

  "Codec support" must {

    "be able to decode a stream of integers with fixed size" in new TestSetup[Int] {
      def encode(i: Int) = ByteString((i >>> 24).toByte, (i >>> 16).toByte, (i >>> 8).toByte, i.toByte)
      def testSequence = List(1, 2, 3, Int.MaxValue, 0, Int.MinValue)
      def decoder = new Decoder[Int] {
        val start = decode(IntField(emit))
      }
    }

    "be able to decode a stream of integers with variable size" in new TestSetup[Int] {
      def encode(i: Int) =
        if (i < 64) ByteString(i.toByte)
        else if (i < 16384) ByteString(((i >> 8) | 0x40).toByte, i.toByte)
        else if (i < 4194304) ByteString(((i >> 16) | 0x80).toByte, (i >> 8).toByte, i.toByte)
        else if (i < 1073741824) ByteString(((i >> 24) | 0xc0).toByte, (i >> 16).toByte, (i >> 8).toByte, i.toByte)
        else throw new IllegalArgumentException(s"Only nonnegative integers up to 2^30 may be represented. Input was: $i")

      def testSequence = List(1, 2, 3, 16384, 4194304, 1073741823, 4, 3, 22, 2048, 65537)

      def decoder = new Decoder[Int] {
        val start = decode(VarintField(emit))
      }
    }

    "be able to decode a stream of strings with a length header" in new TestSetup[String] {
      def encode(s: String) = {
        ByteString((s.length >>> 24).toByte, (s.length >>> 16).toByte, (s.length >>> 8).toByte, s.length.toByte) ++
          ByteString(s, "UTF-8")
      }

      def testSequence = List("test1", "", "longertest", "\n", "\r", "another string", "", "")

      def decoder = new Decoder[String] {
        var frameLength = 0

        val start = decode(IntField { length ⇒ frameLength = length; decodeBody })
        val decodeBody = read(frameLength) { (buf, start, end) ⇒ emit(buf.slice(start, end).utf8String) }
      }
    }

    "be able to decode a stream of frames containing sequences" in new TestSetup[List[Int]] {
      override def encode(in: List[Int]): ByteString = {
        var header = ByteString((in.size >>> 24).toByte, (in.size >>> 16).toByte, (in.size >>> 8).toByte, in.size.toByte)
        for (i ← in) header ++= ByteString((i >>> 24).toByte, (i >>> 16).toByte, (i >>> 8).toByte, i.toByte)
        header
      }

      override def testSequence: scala.collection.immutable.Seq[List[Int]] = List(
        List(1),
        List(3, 4, 16384, Int.MaxValue),
        Nil)

      override def decoder: Decoder[List[Int]] = new Decoder[List[Int]] {
        var remaining = 0
        var acc: List[Int] = Nil

        val start = decode(IntField { size ⇒
          if (size == 0) emit(Nil)
          else {
            remaining = size
            acc = Nil
            decodeElements
          }
        })

        lazy val decodeElements: DecodeStep = decode(IntField { elem ⇒
          remaining -= 1
          acc ::= elem
          if (remaining > 0) decodeElements
          else emit(acc.reverse)
        })
      }
    }

    "be able to decode a stream of frames containing sequences, streaming the elements" in {
      def encode(in: List[Int]): ByteString = {
        var header = ByteString((in.size >>> 24).toByte, (in.size >>> 16).toByte, (in.size >>> 8).toByte, in.size.toByte)
        for (i ← in) header ++= ByteString((i >>> 24).toByte, (i >>> 16).toByte, (i >>> 8).toByte, i.toByte)
        header
      }

      val testSequence: scala.collection.immutable.Seq[List[Int]] = Vector(
        List(1),
        List(3, 4, 16384, Int.MaxValue),
        Nil)

      val decoder: Decoder[Int] = new Decoder[Int] {
        var remaining = 0
        var acc: List[Int] = Nil

        lazy val start: DecodeStep = decode(IntField { size ⇒
          if (size == 0) start
          else {
            remaining = size
            decodeElements
          }
        })

        lazy val decodeElements: DecodeStep = decode(IntField { elem ⇒
          remaining -= 1
          emitIntermediate(elem)
          if (remaining > 0) decodeElements
          else start
        })
      }

      val decoded = decodingTransform(rechunk(Flow(testSequence) map encode), decoder)
        .fold(Vector.empty[Int])((acc, in) ⇒ acc :+ in).toFuture(materializer)

      Await.result(decoded, 3.seconds) should be(testSequence.flatten)
    }

    "be able to decode a stream of delimiter bounded elements" in new TestSetup[String] {
      override def encode(in: String): ByteString = ByteString(in + "\r\n")
      override def testSequence: scala.collection.immutable.Seq[String] = List(
        "", "hello", "world", "\r", "\n", "", "still living")
      override def decoder: Decoder[String] = new Decoder[String] {
        val start: DecodeStep = decode(DelimiterEncodedField(ByteString("\r\n")) { bytes ⇒
          emit(bytes.utf8String.stripSuffix("\r\n"))
        })
      }
    }

    "be able to decode a string length header, then a stream of bytes" in {
      def encode(in: ByteString): ByteString = {
        val header = ByteString(s"${in.size}\r\n")
        header ++ in
      }

      val testSequence: scala.collection.immutable.Seq[ByteString] = Vector(
        ByteString("Hello"),
        ByteString(" "),
        ByteString(""),
        ByteString(""),
        ByteString("world"),
        ByteString("!"))

      val decoder: Decoder[ByteString] = new Decoder[ByteString] {
        var remaining = 0

        val start: DecodeStep = decode(DelimiterEncodedField(ByteString("\r\n")) { header ⇒
          remaining = Integer.parseInt(header.utf8String.stripSuffix("\r\n"))
          readBody
        })

        lazy val readBody: DecodeStep = read(atLeast = 1, atMost = remaining) { (buf, start, end) ⇒
          remaining -= end - start
          if (remaining == 0) emit(buf.slice(start, end))
          else {
            emitIntermediate(buf.slice(start, end))
            readBody
          }
        }

      }

      val decoded = decodingTransform(rechunk(Flow(testSequence) map encode), decoder)
        .fold(Vector.empty[ByteString])((acc, in) ⇒ acc :+ in).toFuture(materializer)

      Await.result(decoded, 3.seconds).reduceLeft(_ ++ _).utf8String should be("Hello world!")
    }

    sealed trait MyVariantType
    case class MyNumber(i: Int) extends MyVariantType
    case class MyString(s: String) extends MyVariantType

    "be able to decode a sequence of variant type fields" in new TestSetup[MyVariantType] {
      override def encode(in: MyVariantType): ByteString = in match {
        case MyNumber(i) ⇒
          ByteString(0) ++ ByteString((i >>> 24).toByte, (i >>> 16).toByte, (i >>> 8).toByte, i.toByte)
        case MyString(s) ⇒
          ByteString(1) ++ ByteString(s + "\r\n")
      }
      override def testSequence = Vector(
        MyNumber(Int.MaxValue), MyNumber(0), MyString(""), MyNumber(-1), MyString("boo"), MyString("\n"),
        MyString("\r"), MyNumber(42))
      override def decoder: Decoder[MyVariantType] = new Decoder[MyVariantType] {
        val start = read(1) { (buf, start, end) ⇒
          buf(start) match {
            case 0 ⇒ decodeInt
            case 1 ⇒ decodeString
          }
        }

        val decodeInt = decode(IntField { i ⇒
          emit(MyNumber(i))
        })

        val decodeString = decode(DelimiterEncodedField(ByteString("\r\n")) { s ⇒
          emit(MyString(s.utf8String.stripSuffix("\r\n")))
        })

      }
    }

  }

}
