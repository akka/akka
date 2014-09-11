/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.unmarshalling

import scala.xml.NodeSeq
import scala.concurrent.duration._
import scala.concurrent.Await
import org.scalatest.matchers.Matcher
import org.scalatest.{ BeforeAndAfterAll, FreeSpec, Matchers }
import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import akka.stream.{ FlowMaterializer, MaterializerSettings }
import akka.http.model._
import akka.http.util._
import headers._
import MediaTypes._

class UnmarshallingSpec extends FreeSpec with Matchers with BeforeAndAfterAll {
  implicit val system = ActorSystem(getClass.getSimpleName)

  import system.dispatcher

  val materializerSettings = MaterializerSettings(system)
    .withDispatcher("akka.test.stream-dispatcher")

  implicit val materializer = FlowMaterializer(materializerSettings)

  "The PredefinedFromEntityUnmarshallers." - {
    "stringUnmarshaller should unmarshal `text/plain` content in UTF-8 to Strings" in {
      Unmarshal(HttpEntity("Hällö")).to[String] should evaluateTo("Hällö")
    }
    "charArrayUnmarshaller should unmarshal `text/plain` content in UTF-8 to char arrays" in {
      Unmarshal(HttpEntity("árvíztűrő ütvefúrógép")).to[Array[Char]] should evaluateTo("árvíztűrő ütvefúrógép".toCharArray)
    }
    "nodeSeqUnmarshaller should unmarshal `text/xml` content in UTF-8 to NodeSeqs" in {
      Unmarshal(HttpEntity(`text/xml`, "<int>Hällö</int>")).to[NodeSeq].map(_.text) should evaluateTo("Hällö")
    }
  }

  "The MultipartUnmarshallers." - {

    "multipartContentUnmarshaller should correctly unmarshal 'multipart/*' content with" - {
      "one empty part" in {
        Unmarshal(HttpEntity(`multipart/mixed` withBoundary "XYZABC",
          """--XYZABC
              |--XYZABC--""".stripMargin)).to[MultipartContent] should haveParts()
      }
      "with one part" in {
        Unmarshal(HttpEntity(`multipart/form-data` withBoundary "-",
          """---
              |Content-type: text/plain; charset=UTF8
              |content-disposition: form-data; name="email"
              |
              |test@there.com
              |-----""".stripMarginWithNewline("\r\n"))).to[MultipartContent] should haveParts(
          BodyPart(
            HttpEntity(ContentTypes.`text/plain(UTF-8)`, "test@there.com"),
            List(`Content-Disposition`(ContentDispositionTypes.`form-data`, Map("name" -> "email")))))
      }
      "with two different parts" in {
        Unmarshal(HttpEntity(`multipart/mixed` withBoundary "12345",
          """--12345
              |
              |first part, with a trailing newline
              |
              |--12345
              |Content-Type: application/octet-stream
              |Content-Transfer-Encoding: binary
              |
              |filecontent
              |--12345--""".stripMarginWithNewline("\r\n"))).to[MultipartContent] should haveParts(
          BodyPart(HttpEntity(ContentTypes.`text/plain(UTF-8)`, "first part, with a trailing newline\r\n")),
          BodyPart(
            HttpEntity(`application/octet-stream`, "filecontent"),
            List(RawHeader("Content-Transfer-Encoding", "binary"))))
      }
    }

    "multipartContentUnmarshaller should reject illegal multipart content" in {
      val mpc = Unmarshal(HttpEntity(`multipart/form-data` withBoundary "-",
        """---
          |Content-type: text/plain; charset=UTF8
          |Content-type: application/json
          |content-disposition: form-data; name="email"
          |
          |test@there.com
          |-----""".stripMarginWithNewline("\r\n")))
        .to[MultipartContent]
        .await(1.second)
      Await.result(Flow(mpc.parts).toFuture().failed, 1.second).getMessage shouldEqual
        "multipart part must not contain more than one Content-Type header"
    }

    "multipartByteRangesUnmarshaller should correctly unmarshal multipart/byteranges content with two different parts" in {
      Unmarshal(HttpEntity(`multipart/byteranges` withBoundary "12345",
        """--12345
          |Content-Range: bytes 0-2/26
          |Content-Type: text/plain
          |
          |ABC
          |--12345
          |Content-Range: bytes 23-25/26
          |Content-Type: text/plain
          |
          |XYZ
          |--12345--""".stripMarginWithNewline("\r\n"))).to[MultipartByteRanges] should haveParts(
        BodyPart(HttpEntity(ContentTypes.`text/plain`, "ABC"), List(`Content-Range`(ContentRange(0, 2, 26)))),
        BodyPart(HttpEntity(ContentTypes.`text/plain`, "XYZ"), List(`Content-Range`(ContentRange(23, 25, 26)))))
    }

    "multipartFormDataUnmarshaller should correctly unmarshal 'multipart/form-data' content" - {
      "with one element" in {
        Unmarshal(HttpEntity(`multipart/form-data` withBoundary "XYZABC",
          """--XYZABC
            |content-disposition: form-data; name=email
            |
            |test@there.com
            |--XYZABC--""".stripMarginWithNewline("\r\n"))).to[MultipartFormData] should haveFormData(
          "email" -> BodyPart(HttpEntity(ContentTypes.`application/octet-stream`, "test@there.com"), "email"))
      }
      "with a file" in {
        Unmarshal(HttpEntity(`multipart/form-data` withBoundary "XYZABC",
          """--XYZABC
            |Content-Disposition: form-data; name="email"
            |
            |test@there.com
            |--XYZABC
            |Content-Disposition: form-data; name="userfile"; filename="test.dat"
            |Content-Type: application/pdf
            |Content-Transfer-Encoding: binary
            |
            |filecontent
            |--XYZABC--""".stripMarginWithNewline("\r\n"))).to[StrictMultipartFormData] should haveFormData(
          "email" -> BodyPart(
            HttpEntity(ContentTypes.`application/octet-stream`, "test@there.com"),
            List(`Content-Disposition`(ContentDispositionTypes.`form-data`, Map("name" -> "email")))),
          "userfile" -> BodyPart(
            HttpEntity(MediaTypes.`application/pdf`, "filecontent"),
            List(RawHeader("Content-Transfer-Encoding", "binary"),
              `Content-Disposition`(ContentDispositionTypes.`form-data`, Map("name" -> "userfile", "filename" -> "test.dat")))))
      }
      // TODO: reactivate after multipart/form-data unmarshalling integrity verification is implemented
      //
      //      "reject illegal multipart content" in {
      //        val Left(MalformedContent(msg, _)) = HttpEntity(`multipart/form-data` withBoundary "XYZABC", "--noboundary--").as[MultipartFormData]
      //        msg === "Missing start boundary"
      //      }
      //      "reject illegal form-data content" in {
      //        val Left(MalformedContent(msg, _)) = HttpEntity(`multipart/form-data` withBoundary "XYZABC",
      //          """|--XYZABC
      //            |content-disposition: form-data; named="email"
      //            |
      //            |test@there.com
      //            |--XYZABC--""".stripMargin).as[MultipartFormData]
      //        msg === "Illegal multipart/form-data content: unnamed body part (no Content-Disposition header or no 'name' parameter)"
      //      }
    }
  }

  override def afterAll() = system.shutdown()

  def evaluateTo[T](value: T): Matcher[Deferrable[T]] =
    equal(value).matcher[T] compose (_.await(1.second))

  def haveParts[T <: MultipartParts](parts: BodyPart*): Matcher[Deferrable[T]] =
    equal(parts).matcher[Seq[BodyPart]] compose {
      _.flatMap(x ⇒ Deferrable(Flow(x.parts).grouped(100).toFuture()))
        .recover { case _: NoSuchElementException ⇒ Nil }
        .await(1.second)
    }

  def haveFormData(fields: (String, BodyPart)*): Matcher[Deferrable[MultipartFormData]] =
    equal(fields).matcher[Seq[(String, BodyPart)]] compose {
      _.flatMap(x ⇒ Deferrable(Flow(x.parts).grouped(100).toFuture()))
        .recover { case _: NoSuchElementException ⇒ Nil }
        .map {
          _ map { part ⇒
            part.headers.collectFirst {
              case `Content-Disposition`(ContentDispositionTypes.`form-data`, params) ⇒ params("name")
            }.get -> part
          }
        }.await(1.second)
    }
}
