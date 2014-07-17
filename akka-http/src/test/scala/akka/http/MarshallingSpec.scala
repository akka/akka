/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import scala.collection.immutable.ListMap
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import org.scalatest.{ BeforeAndAfterAll, Matchers, FreeSpec }
import akka.util.Timeout
import akka.actor.ActorSystem
import akka.http.marshalling.{ ToEntityMarshallers, MultipartMarshallers }
import akka.stream.{ FlowMaterializer, MaterializerSettings }
import akka.http.util._
import akka.http.model._
import headers._
import MediaTypes._
import HttpCharsets._

class MarshallingSpec extends FreeSpec with Matchers with BeforeAndAfterAll with MultipartMarshallers {
  implicit val system = ActorSystem(getClass.getSimpleName)
  import system.dispatcher
  val materializerSettings = MaterializerSettings(dispatcher = "akka.test.stream-dispatcher")
  implicit val materializer = FlowMaterializer(materializerSettings)

  "The PredefinedToEntityMarshallers." - {
    "StringMarshaller should marshal strings to `text/plain` content in UTF-8" in {
      marshal("Ha“llo") shouldEqual HttpEntity("Ha“llo")
    }
    "CharArrayMarshaller should marshal char arrays to `text/plain` content in UTF-8" in {
      marshal("Ha“llo".toCharArray) shouldEqual HttpEntity("Ha“llo")
    }
    "NodeSeqMarshaller should marshal xml snippets to `text/xml` content in UTF-8" in {
      marshal(<employee><nr>Ha“llo</nr></employee>) shouldEqual
        HttpEntity(ContentType(`text/xml`, `UTF-8`), "<employee><nr>Ha“llo</nr></employee>")
    }
    "FormDataMarshaller should marshal FormData instances to application/x-www-form-urlencoded content" in {
      marshal(FormData(Map("name" -> "Bob", "pass" -> "hällo", "admin" -> ""))) shouldEqual
        HttpEntity(ContentType(`application/x-www-form-urlencoded`, `UTF-8`), "name=Bob&pass=h%C3%A4llo&admin=")
    }
  }

  "The GenericMarshallers." - {
    "optionMarshaller should enable marshalling of Option[T]" in {
      marshal(Some("Ha“llo")) shouldEqual HttpEntity("Ha“llo")
      marshal(None: Option[String]) shouldEqual HttpEntity.Empty
    }
    "eitherMarshaller should enable marshalling of Either[A, B]" in {
      marshal[Either[Array[Char], String]](Right("right")) shouldEqual HttpEntity("right")
      marshal[Either[Array[Char], String]](Left("left".toCharArray)) shouldEqual HttpEntity("left")
    }
  }

  "The MultipartMarshallers." - {
    "multipartContentMarshaller should correctly marshal multipart content with" - {
      "one empty part" in {
        marshal(MultipartContent(BodyPart(""))) shouldEqual HttpEntity(
          contentType = ContentType(`multipart/mixed` withBoundary randomBoundary),
          string = s"""--$randomBoundary
                      |Content-Type: text/plain; charset=UTF-8
                      |
                      |
                      |--$randomBoundary--""".stripMarginWithNewline("\r\n"))
      }
      "one non-empty part" in {
        marshal(MultipartContent(BodyPart(
          entity = HttpEntity(ContentType(`text/plain`, `UTF-8`), "test@there.com"),
          headers = `Content-Disposition`(ContentDispositionTypes.`form-data`, Map("name" -> "email")) :: Nil))) shouldEqual
          HttpEntity(
            contentType = ContentType(`multipart/mixed` withBoundary randomBoundary),
            string = s"""--$randomBoundary
                        |Content-Type: text/plain; charset=UTF-8
                        |Content-Disposition: form-data; name=email
                        |
                        |test@there.com
                        |--$randomBoundary--""".stripMarginWithNewline("\r\n"))
      }
      "two different parts" in {
        marshal(MultipartContent(
          BodyPart(HttpEntity(ContentType(`text/plain`, Some(`US-ASCII`)), "first part, with a trailing linebreak\r\n")),
          BodyPart(
            HttpEntity(ContentType(`application/octet-stream`), "filecontent"),
            RawHeader("Content-Transfer-Encoding", "binary") :: Nil))) shouldEqual
          HttpEntity(
            contentType = ContentType(`multipart/mixed` withBoundary randomBoundary),
            string = s"""--$randomBoundary
                      |Content-Type: text/plain; charset=US-ASCII
                      |
                      |first part, with a trailing linebreak
                      |
                      |--$randomBoundary
                      |Content-Type: application/octet-stream
                      |Content-Transfer-Encoding: binary
                      |
                      |filecontent
                      |--$randomBoundary--""".stripMarginWithNewline("\r\n"))
      }
    }

    "multipartFormDataMarshaller should correctly marshal 'multipart/form-data' content with" - {
      "two fields" in {
        marshal(MultipartFormData(ListMap(
          "surname" -> BodyPart("Mike"),
          "age" -> BodyPart(marshal(<int>42</int>))))) shouldEqual
          HttpEntity(
            contentType = ContentType(`multipart/form-data` withBoundary randomBoundary),
            string = s"""--$randomBoundary
                      |Content-Type: text/plain; charset=UTF-8
                      |Content-Disposition: form-data; name=surname
                      |
                      |Mike
                      |--$randomBoundary
                      |Content-Type: text/xml; charset=UTF-8
                      |Content-Disposition: form-data; name=age
                      |
                      |<int>42</int>
                      |--$randomBoundary--""".stripMarginWithNewline("\r\n"))
      }

      "two fields having a custom `Content-Disposition`" in {
        marshal(MultipartFormData(
          BodyPart(
            HttpEntity(`text/csv`, "name,age\r\n\"John Doe\",20\r\n"),
            List(`Content-Disposition`(ContentDispositionTypes.`form-data`, Map("name" -> "attachment[0]", "filename" -> "attachment.csv")))),
          BodyPart(
            HttpEntity("name,age\r\n\"John Doe\",20\r\n".getBytes),
            List(
              `Content-Disposition`(ContentDispositionTypes.`form-data`, Map("name" -> "attachment[1]", "filename" -> "attachment.csv")),
              RawHeader("Content-Transfer-Encoding", "binary"))))) shouldEqual
          HttpEntity(
            contentType = ContentType(`multipart/form-data` withBoundary randomBoundary),
            string = s"""--$randomBoundary
                        |Content-Type: text/csv
                        |Content-Disposition: form-data; name="attachment[0]"; filename=attachment.csv
                        |
                        |name,age
                        |"John Doe",20
                        |
                        |--$randomBoundary
                        |Content-Type: application/octet-stream
                        |Content-Disposition: form-data; name="attachment[1]"; filename=attachment.csv
                        |Content-Transfer-Encoding: binary
                        |
                        |name,age
                        |"John Doe",20
                        |
                        |--$randomBoundary--""".stripMarginWithNewline("\r\n"))
      }
    }
  }

  override def afterAll() = system.shutdown()

  def marshal[T: ToEntityMarshallers](value: T): HttpEntity.Strict =
    Marshal(value).to[HttpEntity].await.toStrict(1.second, materializer).await

  protected class FixedRandom extends java.util.Random {
    override def nextBytes(array: Array[Byte]): Unit = "my-stable-boundary".getBytes("UTF-8").copyToArray(array)
  }
  override protected val multipartBoundaryRandom = new FixedRandom // fix for stable value

  implicit class PimpedFuture[T](underlying: Future[T]) {
    def await(implicit timeout: Timeout = 1.second): T = Await.result(underlying, timeout.duration)
  }
}
