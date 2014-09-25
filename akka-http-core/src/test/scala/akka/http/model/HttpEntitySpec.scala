/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import org.scalatest.{ BeforeAndAfterAll, MustMatchers, FreeSpec }
import akka.util.ByteString
import org.scalatest.matchers.Matcher
import akka.http.model.HttpEntity._
import akka.actor.ActorSystem
import akka.stream.scaladsl2.{ FutureSink, FlowFrom, FlowMaterializer }
import com.typesafe.config.{ ConfigFactory, Config }
import scala.concurrent.{ Promise, Await }
import scala.concurrent.duration._
import java.util.concurrent.TimeoutException

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
          Default(tpe, 42, FlowFrom(neverCompleted.future)).toStrict(100.millis).await(150.millis)
        }.getMessage must be("HttpEntity.toStrict timed out after 100 milliseconds while still waiting for outstanding data")
      }
    }
  }

  def source[T](elems: T*) = FlowFrom(elems.toList)

  def collectBytesTo(bytes: ByteString*): Matcher[HttpEntity] =
    equal(bytes.toVector).matcher[Seq[ByteString]].compose { entity ⇒
      val future = entity.dataBytes.grouped(1000).runWithSink(FutureSink[Seq[ByteString]])
      Await.result(future, 250.millis)
    }

  def strictifyTo(strict: Strict): Matcher[HttpEntity] =
    equal(strict).matcher[Strict].compose(_.toStrict(250.millis).await(250.millis))
}
