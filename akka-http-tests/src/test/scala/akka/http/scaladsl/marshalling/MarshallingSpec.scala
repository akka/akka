/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.marshalling

import akka.http.scaladsl.testkit.MarshallingTestUtils
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._

import scala.collection.immutable.ListMap
import org.scalatest.{ BeforeAndAfterAll, FreeSpec, Matchers }
import akka.actor.ActorSystem
import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl.Source
import akka.http.impl.util._
import akka.http.scaladsl.model._
import headers._
import HttpCharsets._
import MediaTypes._

class MarshallingSpec extends FreeSpec with Matchers with BeforeAndAfterAll with MultipartMarshallers with MarshallingTestUtils {
  implicit val system = ActorSystem(getClass.getSimpleName)
  implicit val materializer = ActorFlowMaterializer()
  import system.dispatcher

  "The PredefinedToEntityMarshallers." - {
    "StringMarshaller should marshal strings to `text/plain` content in UTF-8" in {
      marshal("Ha“llo") shouldEqual HttpEntity("Ha“llo")
    }
    "CharArrayMarshaller should marshal char arrays to `text/plain` content in UTF-8" in {
      marshal("Ha“llo".toCharArray) shouldEqual HttpEntity("Ha“llo")
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
    "multipartMarshaller should correctly marshal multipart content with" - {
      "one empty part" in {
        marshal(Multipart.General(`multipart/mixed`, Multipart.General.BodyPart.Strict(""))) shouldEqual HttpEntity(
          contentType = ContentType(`multipart/mixed` withBoundary randomBoundary),
          string = s"""--$randomBoundary
                      |Content-Type: text/plain; charset=UTF-8
                      |
                      |
                      |--$randomBoundary--""".stripMarginWithNewline("\r\n"))
      }
      "one non-empty part" in {
        marshal(Multipart.General(`multipart/alternative`, Multipart.General.BodyPart.Strict(
          entity = HttpEntity(ContentType(`text/plain`, `UTF-8`), "test@there.com"),
          headers = `Content-Disposition`(ContentDispositionTypes.`form-data`, Map("name" -> "email")) :: Nil))) shouldEqual
          HttpEntity(
            contentType = ContentType(`multipart/alternative` withBoundary randomBoundary),
            string = s"""--$randomBoundary
                        |Content-Type: text/plain; charset=UTF-8
                        |Content-Disposition: form-data; name=email
                        |
                        |test@there.com
                        |--$randomBoundary--""".stripMarginWithNewline("\r\n"))
      }
      "two different parts" in {
        marshal(Multipart.General(`multipart/related`,
          Multipart.General.BodyPart.Strict(HttpEntity(ContentType(`text/plain`, Some(`US-ASCII`)), "first part, with a trailing linebreak\r\n")),
          Multipart.General.BodyPart.Strict(
            HttpEntity(ContentType(`application/octet-stream`), "filecontent"),
            RawHeader("Content-Transfer-Encoding", "binary") :: Nil))) shouldEqual
          HttpEntity(
            contentType = ContentType(`multipart/related` withBoundary randomBoundary),
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
        marshal(Multipart.FormData(ListMap(
          "surname" -> HttpEntity("Mike"),
          "age" -> marshal(<int>42</int>)))) shouldEqual
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
        marshal(Multipart.FormData(Source(List(
          Multipart.FormData.BodyPart("attachment[0]", HttpEntity(`text/csv`, "name,age\r\n\"John Doe\",20\r\n"),
            Map("filename" -> "attachment.csv")),
          Multipart.FormData.BodyPart("attachment[1]", HttpEntity("naice!".getBytes),
            Map("filename" -> "attachment2.csv"), List(RawHeader("Content-Transfer-Encoding", "binary"))))))) shouldEqual
          HttpEntity(
            contentType = ContentType(`multipart/form-data` withBoundary randomBoundary),
            string = s"""--$randomBoundary
                        |Content-Type: text/csv
                        |Content-Disposition: form-data; filename=attachment.csv; name="attachment[0]"
                        |
                        |name,age
                        |"John Doe",20
                        |
                        |--$randomBoundary
                        |Content-Type: application/octet-stream
                        |Content-Disposition: form-data; filename=attachment2.csv; name="attachment[1]"
                        |Content-Transfer-Encoding: binary
                        |
                        |naice!
                        |--$randomBoundary--""".stripMarginWithNewline("\r\n"))
      }
    }
  }

  override def afterAll() = system.shutdown()

  protected class FixedRandom extends java.util.Random {
    override def nextBytes(array: Array[Byte]): Unit = "my-stable-boundary".getBytes("UTF-8").copyToArray(array)
  }
  override protected val multipartBoundaryRandom = new FixedRandom // fix for stable value
}
