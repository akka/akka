/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import java.nio.ByteOrder

import akka.stream.scaladsl.Framing.FramingException
import akka.stream.stage.{ Context, PushPullStage, SyncDirective, TerminationDirective }
import akka.stream.testkit.{ TestSubscriber, TestPublisher }
import akka.testkit.AkkaSpec
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.util.{ ByteString, ByteStringBuilder }

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.util.Random

class FramingSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)
  implicit val materializer = ActorMaterializer(settings)

  class Rechunker extends PushPullStage[ByteString, ByteString] {
    private var rechunkBuffer = ByteString.empty

    override def onPush(chunk: ByteString, ctx: Context[ByteString]): SyncDirective = {
      rechunkBuffer ++= chunk
      rechunk(ctx)
    }

    override def onPull(ctx: Context[ByteString]): SyncDirective = {
      rechunk(ctx)
    }

    override def onUpstreamFinish(ctx: Context[ByteString]): TerminationDirective = {
      if (rechunkBuffer.isEmpty) ctx.finish()
      else ctx.absorbTermination()
    }

    private def rechunk(ctx: Context[ByteString]): SyncDirective = {
      if (!ctx.isFinishing && ThreadLocalRandom.current().nextBoolean()) ctx.pull()
      else {
        val nextChunkSize =
          if (rechunkBuffer.isEmpty) 0
          else ThreadLocalRandom.current().nextInt(0, rechunkBuffer.size + 1)
        val newChunk = rechunkBuffer.take(nextChunkSize)
        rechunkBuffer = rechunkBuffer.drop(nextChunkSize)
        if (ctx.isFinishing && rechunkBuffer.isEmpty) ctx.pushAndFinish(newChunk)
        else ctx.push(newChunk)
      }
    }
  }

  val rechunk = Flow[ByteString].transform(() ⇒ new Rechunker).named("rechunker")

  "Delimiter bytes based framing" must {

    val delimiterBytes = List("\n", "\r\n", "FOO").map(ByteString(_))
    val baseTestSequences = List("", "foo", "hello world").map(ByteString(_))

    // Helper to simplify testing
    def simpleLines(delimiter: String, maximumBytes: Int, allowTruncation: Boolean = true) =
      Framing.delimiter(ByteString(delimiter), maximumBytes, allowTruncation).map(_.utf8String)
        .named("lineFraming")

    def completeTestSequences(delimiter: ByteString): immutable.Iterable[ByteString] =
      for (prefix ← delimiter.indices; s ← baseTestSequences)
        yield delimiter.take(prefix) ++ s

    "work with various delimiters and test sequences" in {
      for (delimiter ← delimiterBytes; _ ← 1 to 100) {
        val f = Source(completeTestSequences(delimiter))
          .map(_ ++ delimiter)
          .via(rechunk)
          .via(Framing.delimiter(delimiter, 256))
          .grouped(1000)
          .runWith(Sink.head)

        Await.result(f, 3.seconds) should be(completeTestSequences(delimiter))
      }
    }

    "Respect maximum line settings" in {
      // The buffer will contain more than 1 bytes, but the individual frames are less
      Await.result(
        Source.single(ByteString("a\nb\nc\nd\n")).via(simpleLines("\n", 1)).limit(100).runWith(Sink.seq),
        3.seconds) should ===(List("a", "b", "c", "d"))

      an[FramingException] should be thrownBy {
        Await.result(
          Source.single(ByteString("ab\n")).via(simpleLines("\n", 1)).limit(100).runWith(Sink.seq),
          3.seconds)
      }

      an[FramingException] should be thrownBy {
        Await.result(
          Source.single(ByteString("aaa")).via(simpleLines("\n", 2)).limit(100).runWith(Sink.seq),
          3.seconds)
      }
    }

    "work with empty streams" in {
      Await.result(
        Source.empty.via(simpleLines("\n", 256)).runFold(Vector.empty[String])(_ :+ _),
        3.seconds) should ===(Vector.empty)
    }

    "report truncated frames" in {
      an[FramingException] should be thrownBy {
        Await.result(
          Source.single(ByteString("I have no end"))
            .via(simpleLines("\n", 256, allowTruncation = false))
            .grouped(1000)
            .runWith(Sink.head),
          3.seconds)
      }
    }

    "allow truncated frames if configured so" in {
      Await.result(
        Source.single(ByteString("I have no end"))
          .via(simpleLines("\n", 256, allowTruncation = true))
          .grouped(1000)
          .runWith(Sink.head),
        3.seconds) should ===(List("I have no end"))
    }

  }

  "Length field based framing" must {

    val referenceChunk = ByteString(scala.util.Random.nextString(0x100001))

    val byteOrders = List(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)
    val frameLengths = List(0, 1, 2, 3, 0xFF, 0x100, 0x101, 0xFFF, 0x1000, 0x1001, 0xFFFF, 0x10000, 0x10001)
    val fieldLengths = List(1, 2, 3, 4)
    val fieldOffsets = List(0, 1, 2, 3, 15, 16, 31, 32, 44, 107)

    def encode(payload: ByteString, fieldOffset: Int, fieldLength: Int, byteOrder: ByteOrder): ByteString = {
      val header = {
        val h = (new ByteStringBuilder).putInt(payload.size)(byteOrder).result()
        byteOrder match {
          case ByteOrder.LITTLE_ENDIAN ⇒ h.take(fieldLength)
          case ByteOrder.BIG_ENDIAN    ⇒ h.drop(4 - fieldLength)
        }
      }

      ByteString(Array.ofDim[Byte](fieldOffset)) ++ header ++ payload
    }

    "work with various byte orders, frame lengths and offsets" in {
      for {
        _ ← 1 to 10
        byteOrder ← byteOrders
        fieldOffset ← fieldOffsets
        fieldLength ← fieldLengths
      } {

        val encodedFrames = frameLengths.filter(_ < (1L << (fieldLength * 8))).map { length ⇒
          val payload = referenceChunk.take(length)
          encode(payload, fieldOffset, fieldLength, byteOrder)
        }

        Await.result(
          Source(encodedFrames)
            .via(rechunk)
            .via(Framing.lengthField(fieldLength, fieldOffset, Int.MaxValue, byteOrder))
            .grouped(10000)
            .runWith(Sink.head),
          3.seconds) should ===(encodedFrames)
      }

    }

    "work with empty streams" in {
      Await.result(
        Source.empty.via(Framing.lengthField(4, 0, Int.MaxValue, ByteOrder.BIG_ENDIAN)).runFold(Vector.empty[ByteString])(_ :+ _),
        3.seconds) should ===(Vector.empty)
    }

    "work with grouped frames" in {
      val groupSize = 5
      val single = encode(referenceChunk.take(100), 0, 1, ByteOrder.BIG_ENDIAN)
      val groupedFrames = (1 to groupSize)
        .map(_ ⇒ single)
        .fold(ByteString.empty)((result, bs) ⇒ result ++ bs)

      val publisher = TestPublisher.probe[ByteString]()
      val substriber = TestSubscriber.manualProbe[ByteString]()

      Source.fromPublisher(publisher)
        .via(Framing.lengthField(1, 0, Int.MaxValue, ByteOrder.BIG_ENDIAN))
        .to(Sink.fromSubscriber(substriber))
        .run()

      val subscription = substriber.expectSubscription()

      publisher.sendNext(groupedFrames)
      publisher.sendComplete()
      for (_ ← 1 to groupSize) {
        subscription.request(1)
        substriber.expectNext(single)
      }
      substriber.expectComplete()
      subscription.cancel()
    }

    "report oversized frames" in {
      an[FramingException] should be thrownBy {
        Await.result(
          Source.single(encode(referenceChunk.take(100), 0, 1, ByteOrder.BIG_ENDIAN))
            .via(Framing.lengthField(1, 0, 99, ByteOrder.BIG_ENDIAN)).runFold(Vector.empty[ByteString])(_ :+ _),
          3.seconds)
      }

      an[FramingException] should be thrownBy {
        Await.result(
          Source.single(encode(referenceChunk.take(100), 49, 1, ByteOrder.BIG_ENDIAN))
            .via(Framing.lengthField(1, 0, 100, ByteOrder.BIG_ENDIAN)).runFold(Vector.empty[ByteString])(_ :+ _),
          3.seconds)
      }
    }

    "report truncated frames" in {
      for {
        //_ ← 1 to 10
        byteOrder ← byteOrders
        fieldOffset ← fieldOffsets
        fieldLength ← fieldLengths
        frameLength ← frameLengths if frameLength < (1 << (fieldLength * 8)) && (frameLength != 0)
      } {

        val fullFrame = encode(referenceChunk.take(frameLength), fieldOffset, fieldLength, byteOrder)
        val partialFrame = fullFrame.dropRight(1)

        an[FramingException] should be thrownBy {
          Await.result(
            Source(List(fullFrame, partialFrame))
              .via(rechunk)
              .via(Framing.lengthField(fieldLength, fieldOffset, Int.MaxValue, byteOrder))
              .grouped(10000)
              .runWith(Sink.head),
            3.seconds)
        }
      }
    }

    "support simple framing adapter" in {
      val rechunkBidi = BidiFlow.fromFlowsMat(rechunk, rechunk)(Keep.left)
      val codecFlow =
        Framing.simpleFramingProtocol(1024)
          .atop(rechunkBidi)
          .atop(Framing.simpleFramingProtocol(1024).reversed)
          .join(Flow[ByteString]) // Loopback

      val testMessages = List.fill(100)(referenceChunk.take(Random.nextInt(1024)))
      Await.result(
        Source(testMessages).via(codecFlow).limit(1000).runWith(Sink.seq),
        3.seconds) should ===(testMessages)
    }

  }

}
