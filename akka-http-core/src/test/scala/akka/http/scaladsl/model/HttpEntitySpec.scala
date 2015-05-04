/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.model

import java.util.concurrent.TimeoutException
import com.typesafe.config.{ ConfigFactory, Config }
import scala.concurrent.{ Promise, Await }
import scala.concurrent.duration._
import org.scalatest.{ BeforeAndAfterAll, MustMatchers, FreeSpec }
import org.scalatest.matchers.Matcher
import akka.util.ByteString
import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.stream.ActorFlowMaterializer
import akka.http.scaladsl.model.HttpEntity._
import akka.http.impl.util.StreamUtils

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
  import system.dispatcher

  implicit val materializer = ActorFlowMaterializer()
  override def afterAll() = system.shutdown()

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
          Await.result(Default(tpe, 42, Source(neverCompleted.future)).toStrict(100.millis), 150.millis)
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
  }

  def source[T](elems: T*) = Source(elems.toList)

  def collectBytesTo(bytes: ByteString*): Matcher[HttpEntity] =
    equal(bytes.toVector).matcher[Seq[ByteString]].compose { entity ⇒
      val future = entity.dataBytes.grouped(1000).runWith(Sink.head)
      Await.result(future, 250.millis)
    }

  def strictifyTo(strict: Strict): Matcher[HttpEntity] =
    equal(strict).matcher[Strict].compose(x ⇒ Await.result(x.toStrict(250.millis), 250.millis))

  def transformTo(strict: Strict): Matcher[HttpEntity] =
    equal(strict).matcher[Strict].compose { x ⇒
      val transformed = x.transformDataBytes(duplicateBytesTransformer)
      Await.result(transformed.toStrict(250.millis), 250.millis)
    }

  def duplicateBytesTransformer(): Flow[ByteString, ByteString, Unit] =
    Flow[ByteString].transform(() ⇒ StreamUtils.byteStringTransformer(doubleChars, () ⇒ trailer))

  def trailer: ByteString = ByteString("--dup")
  def doubleChars(bs: ByteString): ByteString = ByteString(bs.flatMap(b ⇒ Seq(b, b)): _*)
  def doubleChars(str: String): ByteString = doubleChars(ByteString(str))
}
