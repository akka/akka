/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model

import com.typesafe.config.{ Config, ConfigFactory }
import scala.concurrent.Await
import scala.concurrent.duration._
import org.scalatest.{ BeforeAndAfterAll, Inside, Matchers, WordSpec }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.actor.ActorSystem
import headers._

class MultipartSpec extends WordSpec with Matchers with Inside with BeforeAndAfterAll {

  val testConf: Config = ConfigFactory.parseString("""
  akka.event-handlers = ["akka.testkit.TestEventListener"]
  akka.loglevel = WARNING""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  implicit val materializer = ActorMaterializer()
  override def afterAll() = system.terminate()

  "Multipart.General" should {
    "support `toStrict` on the streamed model" in {
      val streamed = Multipart.General(
        MediaTypes.`multipart/mixed`,
        Source(Multipart.General.BodyPart(defaultEntity("data"), List(ETag("xzy"))) :: Nil))
      val strict = Await.result(streamed.toStrict(1.second), 1.second)

      strict shouldEqual Multipart.General(
        MediaTypes.`multipart/mixed`,
        Multipart.General.BodyPart.Strict(HttpEntity("data"), List(ETag("xzy"))))
    }
  }

  "Multipart.FormData" should {
    "support `toStrict` on the streamed model" in {
      val streamed = Multipart.FormData(Source(
        Multipart.FormData.BodyPart("foo", defaultEntity("FOO")) ::
          Multipart.FormData.BodyPart("bar", defaultEntity("BAR")) :: Nil))
      val strict = Await.result(streamed.toStrict(1.second), 1.second)

      strict shouldEqual Multipart.FormData(Map("foo" → HttpEntity("FOO"), "bar" → HttpEntity("BAR")))
    }
  }

  "Multipart.ByteRanges" should {
    "support `toStrict` on the streamed model" in {
      val streamed = Multipart.ByteRanges(Source(
        Multipart.ByteRanges.BodyPart(ContentRange(0, 6), defaultEntity("snippet"), _additionalHeaders = List(ETag("abc"))) ::
          Multipart.ByteRanges.BodyPart(ContentRange(8, 9), defaultEntity("PR"), _additionalHeaders = List(ETag("xzy"))) :: Nil))
      val strict = Await.result(streamed.toStrict(1.second), 1.second)

      strict shouldEqual Multipart.ByteRanges(
        Multipart.ByteRanges.BodyPart.Strict(ContentRange(0, 6), HttpEntity("snippet"), additionalHeaders = List(ETag("abc"))),
        Multipart.ByteRanges.BodyPart.Strict(ContentRange(8, 9), HttpEntity("PR"), additionalHeaders = List(ETag("xzy"))))
    }
  }

  def defaultEntity(content: String) =
    HttpEntity.Default(ContentTypes.`text/plain(UTF-8)`, content.length, Source(ByteString(content) :: Nil))
}
