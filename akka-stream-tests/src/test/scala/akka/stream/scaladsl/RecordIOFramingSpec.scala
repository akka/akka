/*
 * Copyright (C) 2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Framing.FramingException
import akka.testkit.AkkaSpec
import akka.util.ByteString

import scala.collection.immutable.Seq

class RecordIOFramingSpec extends AkkaSpec {

  implicit val mat = ActorMaterializer()

  val FirstRecordData = """{"type": "SUBSCRIBED","subscribed": {"framework_id": {"value":"12220-3440-12532-2345"},"heartbeat_interval_seconds":15.0}"""
  val SecondRecordData = """{"type":"HEARTBEAT"}"""

  val FirstRecordWithPrefix = s"121\n$FirstRecordData"
  val SecondRecordWithPrefix = s"20\n$SecondRecordData"

  val stringSeqSink = (Flow[ByteString] map (_.utf8String) toMat Sink.seq)(Keep.right)

  "RecordIO framing" should {
    "parse a series of records" in {
      // Given
      val recordIOInput = FirstRecordWithPrefix + SecondRecordWithPrefix

      // When
      val result = Source.single(ByteString(recordIOInput)) via
        RecordIOFraming.scanner() runWith
        stringSeqSink

      // Then
      result.futureValue shouldBe Seq(FirstRecordData, SecondRecordData)
    }

    "parse input with additional whitespace" in {
      // Given
      val recordIOInput = s"\t\n $FirstRecordWithPrefix \r\n $SecondRecordWithPrefix \t"

      // When
      val result = Source.single(ByteString(recordIOInput)) via
        RecordIOFraming.scanner() runWith
        stringSeqSink

      // Then
      result.futureValue shouldBe Seq(FirstRecordData, SecondRecordData)
    }

    "parse a chunked stream" in {
      // Given
      val whitespaceChunk = "\n\n"
      val (secondChunk, thirdChunk) = s"\n\n$FirstRecordWithPrefix\n\n".splitAt(50)
      val (fifthChunk, sixthChunk) = s"\n\n$SecondRecordWithPrefix\n\n".splitAt(10)

      val chunkedInput = Seq(
        whitespaceChunk, secondChunk, thirdChunk, whitespaceChunk, fifthChunk, sixthChunk, whitespaceChunk)
        .map(ByteString(_))

      // When
      val result = Source(chunkedInput) via
        RecordIOFraming.scanner() runWith
        stringSeqSink

      // Then
      result.futureValue shouldBe Seq(FirstRecordData, SecondRecordData)
    }

    "handle an empty stream" in {
      // When
      val result =
        Source.empty via
          RecordIOFraming.scanner() runWith
          stringSeqSink

      // Then
      result.futureValue shouldBe Seq.empty
    }

    "handle a stream containing only whitespace" in {
      // Given
      val input = Seq("\n\n", "  ", "\t\t").map(ByteString(_))

      // When
      val result = Source(input) via
        RecordIOFraming.scanner() runWith
        stringSeqSink

      // Then
      result.futureValue shouldBe Seq.empty
    }

    "reject an unparseable record size prefix" in {
      // Given
      val recordIOInput = s"NAN\n$FirstRecordData"

      // When
      val result = Source.single(ByteString(recordIOInput)) via
        RecordIOFraming.scanner(1024) runWith
        stringSeqSink

      // Then
      result.failed.futureValue shouldBe a[NumberFormatException]
    }

    "reject an overly long record size prefix" in {
      // Given
      val infinitePrefixSource = Source.repeat(ByteString("1"))

      // When
      val result = infinitePrefixSource via
        RecordIOFraming.scanner(1024) runWith
        stringSeqSink

      // Then
      result.failed.futureValue shouldBe a[FramingException]
    }

    "reject an overly long record" in {
      // Given
      val recordIOInput = FirstRecordWithPrefix

      // When
      val result = Source.single(ByteString(recordIOInput)) via
        RecordIOFraming.scanner(FirstRecordData.length - 1) runWith
        stringSeqSink

      // Then
      result.failed.futureValue shouldBe a[FramingException]
    }

    "reject a truncated record" in {
      // Given
      val recordIOInput = FirstRecordWithPrefix.dropRight(1)

      // When
      val result = Source.single(ByteString(recordIOInput)) via
        RecordIOFraming.scanner() runWith
        stringSeqSink

      // Then
      result.failed.futureValue shouldBe a[FramingException]
    }
  }
}
