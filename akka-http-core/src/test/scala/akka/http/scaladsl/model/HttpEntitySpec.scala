/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model

import java.util.concurrent.TimeoutException
import akka.NotUsed
import com.typesafe.config.{ ConfigFactory, Config }
import scala.concurrent.{ Promise, Await }
import scala.concurrent.duration._
import org.scalatest.{ BeforeAndAfterAll, MustMatchers, FreeSpec }
import org.scalatest.matchers.{ MatchResult, Matcher }
import akka.util.ByteString
import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.stream.ActorMaterializer
import akka.http.scaladsl.model.HttpEntity._
import akka.http.impl.util.StreamUtils

import scala.util.Random

class HttpEntitySpec extends FreeSpec with MustMatchers with BeforeAndAfterAll {
  val tpe: ContentType = ContentTypes.`application/octet-stream`
  val abc = ByteString("abc")
  val de = ByteString("de")
  val fgh = ByteString("fgh")
  val ijk = ByteString("ijk")

  val testConf: Config = ConfigFactory.parseString("""
  akka.event-handlers = ["akka.testkit.TestEventListener"]
  akka.loglevel = WARNING""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)

  implicit val materializer = ActorMaterializer()
  override def afterAll() = system.terminate()

  val awaitAtMost = 3.seconds

  "HttpEntity" - {
    "support dataBytes" - {
      "Strict" in {
        Strict(tpe, abc) must collectBytesTo(abc)
      }
      "Default" in {
        Default(tpe, 11, source(abc, de, fgh, ijk)) must collectBytesTo(abc, de, fgh, ijk)
      }
      "CloseDelimited" in {
        CloseDelimited(tpe, source(abc, de, fgh, ijk)) must collectBytesTo(abc, de, fgh, ijk)
      }
      "Chunked w/o LastChunk" in {
        Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk))) must collectBytesTo(abc, fgh, ijk)
      }
      "Chunked with LastChunk" in {
        Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)) must collectBytesTo(abc, fgh, ijk)
      }
    }
    "support contentLength" - {
      "Strict" in {
        Strict(tpe, abc).contentLengthOption mustEqual Some(3)
      }
      "Default" in {
        Default(tpe, 11, source(abc, de, fgh, ijk)).contentLengthOption mustEqual Some(11)
      }
      "CloseDelimited" in {
        CloseDelimited(tpe, source(abc, de, fgh, ijk)).contentLengthOption mustEqual None
      }
      "Chunked" in {
        Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk))).contentLengthOption mustEqual None
      }
    }
    "support toStrict" - {
      "Strict" in {
        Strict(tpe, abc) must strictifyTo(Strict(tpe, abc))
      }
      "Default" in {
        Default(tpe, 11, source(abc, de, fgh, ijk)) must
          strictifyTo(Strict(tpe, abc ++ de ++ fgh ++ ijk))
      }
      "CloseDelimited" in {
        CloseDelimited(tpe, source(abc, de, fgh, ijk)) must
          strictifyTo(Strict(tpe, abc ++ de ++ fgh ++ ijk))
      }
      "Chunked w/o LastChunk" in {
        Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk))) must
          strictifyTo(Strict(tpe, abc ++ fgh ++ ijk))
      }
      "Chunked with LastChunk" in {
        Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)) must
          strictifyTo(Strict(tpe, abc ++ fgh ++ ijk))
      }
      "Infinite data stream" in {
        val neverCompleted = Promise[ByteString]()
        intercept[TimeoutException] {
          Await.result(Default(tpe, 42, Source.fromFuture(neverCompleted.future)).toStrict(100.millis), awaitAtMost)
        }.getMessage must be("HttpEntity.toStrict timed out after 100 milliseconds while still waiting for outstanding data")
      }
    }
    "support transformDataBytes" - {
      "Strict" in {
        Strict(tpe, abc) must transformTo(Strict(tpe, doubleChars("abc") ++ trailer))
      }
      "Default" in {
        Default(tpe, 11, source(abc, de, fgh, ijk)) must
          transformTo(Strict(tpe, doubleChars("abcdefghijk") ++ trailer))
      }
      "CloseDelimited" in {
        CloseDelimited(tpe, source(abc, de, fgh, ijk)) must
          transformTo(Strict(tpe, doubleChars("abcdefghijk") ++ trailer))
      }
      "Chunked w/o LastChunk" in {
        Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk))) must
          transformTo(Strict(tpe, doubleChars("abcfghijk") ++ trailer))
      }
      "Chunked with LastChunk" in {
        Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)) must
          transformTo(Strict(tpe, doubleChars("abcfghijk") ++ trailer))
      }
      "Chunked with extra LastChunk" in {
        Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk, LastChunk)) must
          transformTo(Strict(tpe, doubleChars("abcfghijk") ++ trailer))
      }
    }
    "support toString" - {
      "Strict with binary MediaType" in {
        val binaryType = ContentTypes.`application/octet-stream`
        val entity = Strict(binaryType, abc)
        entity must renderStrictDataAs(entity.data.toString())
      }
      "Strict with non-binary MediaType and less than 4096 bytes" in {
        val nonBinaryType = ContentTypes.`application/json`
        val entity = Strict(nonBinaryType, abc)
        entity must renderStrictDataAs(entity.data.decodeString(nonBinaryType.charset.value))
      }
      "Strict with non-binary MediaType and over 4096 bytes" in {
        val utf8Type = ContentTypes.`text/plain(UTF-8)`
        val longString = Random.alphanumeric.take(10000).mkString
        val entity = Strict(utf8Type, ByteString.apply(longString, utf8Type.charset.value))
        entity must renderStrictDataAs(s"${longString.take(4095)} ... (10000 bytes total)")
      }
      "Default" in {
        val entity = Default(tpe, 11, source(abc, de, fgh, ijk))
        entity.toString must include(entity.productPrefix)
      }
      "CloseDelimited" in {
        val entity = CloseDelimited(tpe, source(abc, de, fgh, ijk))
        entity.toString must include(entity.productPrefix)
      }
      "Chunked" in {
        val entity = Chunked(tpe, source(Chunk(abc)))
        entity.toString must include(entity.productPrefix)
      }
    }
    "support withoutSizeLimit" - {
      "Strict" in {
        HttpEntity.Empty.withoutSizeLimit
        withReturnType[UniversalEntity](Strict(tpe, abc).withoutSizeLimit)
        withReturnType[RequestEntity](Strict(tpe, abc).asInstanceOf[RequestEntity].withoutSizeLimit)
        withReturnType[ResponseEntity](Strict(tpe, abc).asInstanceOf[ResponseEntity].withoutSizeLimit)
      }
      "Default" in {
        withReturnType[Default](Default(tpe, 11, source(abc, de, fgh, ijk)).withoutSizeLimit)
        withReturnType[RequestEntity](Default(tpe, 11, source(abc, de, fgh, ijk)).asInstanceOf[RequestEntity].withoutSizeLimit)
        withReturnType[ResponseEntity](Default(tpe, 11, source(abc, de, fgh, ijk)).asInstanceOf[ResponseEntity].withoutSizeLimit)
      }
      "CloseDelimited" in {
        withReturnType[CloseDelimited](CloseDelimited(tpe, source(abc, de, fgh, ijk)).withoutSizeLimit)
        withReturnType[ResponseEntity](CloseDelimited(tpe, source(abc, de, fgh, ijk)).asInstanceOf[ResponseEntity].withoutSizeLimit)
      }
      "Chunked" in {
        withReturnType[Chunked](Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)).withoutSizeLimit)
        withReturnType[RequestEntity](Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)).asInstanceOf[RequestEntity].withoutSizeLimit)
        withReturnType[ResponseEntity](Chunked(tpe, source(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)).asInstanceOf[ResponseEntity].withoutSizeLimit)
      }
    }
  }

  def source[T](elems: T*) = Source(elems.toList)

  def collectBytesTo(bytes: ByteString*): Matcher[HttpEntity] =
    equal(bytes.toVector).matcher[Seq[ByteString]].compose { entity ⇒
      val future = entity.dataBytes.limit(1000).runWith(Sink.seq)
      Await.result(future, awaitAtMost)
    }

  def withReturnType[T](expr: T) = expr

  def strictifyTo(strict: Strict): Matcher[HttpEntity] =
    equal(strict).matcher[Strict].compose(x ⇒ Await.result(x.toStrict(awaitAtMost), awaitAtMost))

  def transformTo(strict: Strict): Matcher[HttpEntity] =
    equal(strict).matcher[Strict].compose { x ⇒
      val transformed = x.transformDataBytes(duplicateBytesTransformer)
      Await.result(transformed.toStrict(awaitAtMost), awaitAtMost)
    }

  def renderStrictDataAs(dataRendering: String): Matcher[Strict] =
    Matcher { strict: Strict ⇒
      val expectedRendering = s"${strict.productPrefix}(${strict.contentType},$dataRendering)"
      MatchResult(
        strict.toString == expectedRendering,
        strict.toString + " != " + expectedRendering,
        strict.toString + " == " + expectedRendering)
    }

  def duplicateBytesTransformer(): Flow[ByteString, ByteString, NotUsed] =
    Flow[ByteString].via(StreamUtils.byteStringTransformer(doubleChars, () ⇒ trailer))

  def trailer: ByteString = ByteString("--dup")
  def doubleChars(bs: ByteString): ByteString = ByteString(bs.flatMap(b ⇒ Seq(b, b)): _*)
  def doubleChars(str: String): ByteString = doubleChars(ByteString(str))
}
