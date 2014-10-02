/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import java.util.concurrent.TimeoutException
import com.typesafe.config.{ ConfigFactory, Config }
import org.reactivestreams.Publisher
import scala.collection.immutable
import scala.concurrent.{ Promise, Await }
import scala.concurrent.duration._
import org.scalatest.{ BeforeAndAfterAll, MustMatchers, FreeSpec }
import org.scalatest.matchers.Matcher
import akka.util.ByteString
import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import akka.stream.{ Transformer, FlowMaterializer }
import akka.stream.impl.SynchronousPublisherFromIterable
import akka.http.model.HttpEntity._

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

  implicit val materializer = FlowMaterializer()
  override def afterAll() = system.shutdown()

  "HttpEntity" - {
    "support dataBytes" - {
      "Strict" in {
        Strict(tpe, abc) must collectBytesTo(abc)
      }
      "Default" in {
        Default(tpe, 11, publisher(abc, de, fgh, ijk)) must collectBytesTo(abc, de, fgh, ijk)
      }
      "CloseDelimited" in {
        CloseDelimited(tpe, publisher(abc, de, fgh, ijk)) must collectBytesTo(abc, de, fgh, ijk)
      }
      "Chunked w/o LastChunk" in {
        Chunked(tpe, publisher(Chunk(abc), Chunk(fgh), Chunk(ijk))) must collectBytesTo(abc, fgh, ijk)
      }
      "Chunked with LastChunk" in {
        Chunked(tpe, publisher(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)) must collectBytesTo(abc, fgh, ijk)
      }
    }
    "support toStrict" - {
      "Strict" in {
        Strict(tpe, abc) must strictifyTo(Strict(tpe, abc))
      }
      "Default" in {
        Default(tpe, 11, publisher(abc, de, fgh, ijk)) must
          strictifyTo(Strict(tpe, abc ++ de ++ fgh ++ ijk))
      }
      "CloseDelimited" in {
        CloseDelimited(tpe, publisher(abc, de, fgh, ijk)) must
          strictifyTo(Strict(tpe, abc ++ de ++ fgh ++ ijk))
      }
      "Chunked w/o LastChunk" in {
        Chunked(tpe, publisher(Chunk(abc), Chunk(fgh), Chunk(ijk))) must
          strictifyTo(Strict(tpe, abc ++ fgh ++ ijk))
      }
      "Chunked with LastChunk" in {
        Chunked(tpe, publisher(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)) must
          strictifyTo(Strict(tpe, abc ++ fgh ++ ijk))
      }
      "Infinite data stream" in {
        val neverCompleted = Promise[ByteString]()
        val stream: Publisher[ByteString] = Flow(neverCompleted.future).toPublisher()
        intercept[TimeoutException] {
          Await.result(Default(tpe, 42, stream).toStrict(100.millis), 150.millis)
        }.getMessage must be("HttpEntity.toStrict timed out after 100 milliseconds while still waiting for outstanding data")
      }
    }
    "support transformDataBytes" - {
      "Strict" in {
        Strict(tpe, abc) must transformTo(Strict(tpe, doubleChars("abc") ++ trailer))
      }
      "Default" in {
        Default(tpe, 11, publisher(abc, de, fgh, ijk)) must
          transformTo(Strict(tpe, doubleChars("abcdefghijk") ++ trailer))
      }
      "CloseDelimited" in {
        CloseDelimited(tpe, publisher(abc, de, fgh, ijk)) must
          transformTo(Strict(tpe, doubleChars("abcdefghijk") ++ trailer))
      }
      "Chunked w/o LastChunk" in {
        Chunked(tpe, publisher(Chunk(abc), Chunk(fgh), Chunk(ijk))) must
          transformTo(Strict(tpe, doubleChars("abcfghijk") ++ trailer))
      }
      "Chunked with LastChunk" in {
        Chunked(tpe, publisher(Chunk(abc), Chunk(fgh), Chunk(ijk), LastChunk)) must
          transformTo(Strict(tpe, doubleChars("abcfghijk") ++ trailer))
      }
    }
  }

  def publisher[T](elems: T*) = SynchronousPublisherFromIterable(elems.toList)

  def collectBytesTo(bytes: ByteString*): Matcher[HttpEntity] =
    equal(bytes.toVector).matcher[Seq[ByteString]].compose { entity ⇒
      val future = Flow(entity.dataBytes).grouped(1000).toFuture()
      Await.result(future, 250.millis)
    }

  def strictifyTo(strict: Strict): Matcher[HttpEntity] =
    equal(strict).matcher[Strict].compose(x ⇒ Await.result(x.toStrict(250.millis), 250.millis))

  def transformTo(strict: Strict): Matcher[HttpEntity] =
    equal(strict).matcher[Strict].compose { x ⇒
      val transformed = x.transformDataBytes(duplicateBytesTransformer)
      Await.result(transformed.toStrict(250.millis), 250.millis)
    }

  def duplicateBytesTransformer(): Transformer[ByteString, ByteString] =
    new Transformer[ByteString, ByteString] {
      def onNext(bs: ByteString): immutable.Seq[ByteString] =
        Vector(doubleChars(bs))

      override def onTermination(e: Option[Throwable]): immutable.Seq[ByteString] =
        Vector(trailer)
    }

  def trailer: ByteString = ByteString("--dup")
  def doubleChars(bs: ByteString): ByteString = ByteString(bs.flatMap(b ⇒ Seq(b, b)): _*)
  def doubleChars(str: String): ByteString = doubleChars(ByteString(str))
}
