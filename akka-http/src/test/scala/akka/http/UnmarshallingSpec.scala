/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import scala.concurrent.duration._
import scala.concurrent.{ Future, Await }
import org.scalatest.{ BeforeAndAfterAll, FreeSpec, Matchers }
import org.scalatest.matchers.Matcher
import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import akka.stream.{ FlowMaterializer, MaterializerSettings }
import akka.http.unmarshalling.Unmarshalling
import akka.http.util._
import akka.http.model._
import MediaTypes._
import headers._

class UnmarshallingSpec extends FreeSpec with Matchers with BeforeAndAfterAll {
  implicit val system = ActorSystem(getClass.getSimpleName)
  val materializerSettings = MaterializerSettings(dispatcher = "akka.test.stream-dispatcher")
  import system.dispatcher
  implicit val materializer = FlowMaterializer(materializerSettings)

  "The PredefinedFromEntityUnmarshallers." - {
    "stringUnmarshaller should unmarshal `text/plain` content in UTF-8 to Strings" in {
      Unmarshal(HttpEntity("Hällö")).to[String] should evaluateTo("Hällö")
    }
    "charArrayUnmarshaller should unmarshal `text/plain` content in UTF-8 to char arrays" in {
      Unmarshal(HttpEntity("árvíztűrő ütvefúrógép")).to[Array[Char]] should evaluateTo("árvíztűrő ütvefúrógép".toCharArray)
    }
    //    "nodeSeqUnmarshaller should unmarshal `text/xml` content in UTF-8 to NodeSeqs" in {
    //      Unmarshal(HttpEntity(`text/xml`, "<int>Hällö</int>")).to[NodeSeq].map(_.map(_.text)) shouldEqual "Hällö"
    //    }
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
      val future = Unmarshal(HttpEntity(`multipart/form-data` withBoundary "-",
        """---
          |Content-type: text/plain; charset=UTF8
          |Content-type: application/json
          |content-disposition: form-data; name="email"
          |
          |test@there.com
          |-----""".stripMarginWithNewline("\r\n"))).to[MultipartContent]
      Await.result(future, 1.second) match {
        case Unmarshalling.Success(x) ⇒
          Await.result(Flow(x.parts).toFuture(materializer).failed, 1.second).getMessage shouldEqual
            "multipart part must not contain more than one Content-Type header"
        case x ⇒ fail(x.toString)
      }
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
          "email" -> BodyPart(
            HttpEntity(ContentTypes.`application/octet-stream`, "test@there.com"), "email"))
      }
      //      "with a file" in {
      //        HttpEntity(`multipart/form-data` withBoundary "XYZABC",
      //          """|--XYZABC
      //            |Content-Disposition: form-data; name="email"
      //            |
      //            |test@there.com
      //            |--XYZABC
      //            |Content-Disposition: form-data; name="userfile"; filename="test.dat"
      //            |Content-Type: application/octet-stream
      //            |Content-Transfer-Encoding: binary
      //            |
      //            |filecontent
      //            |--XYZABC--""".stripMargin).as[MultipartFormData].get.fields.map {
      //          case part @ BodyPart(entity, _) ⇒
      //            part.name.get + ": " + entity.as[String].get + part.filename.map(",filename: " + _).getOrElse("")
      //        }.mkString("|") === "email: test@there.com|userfile: filecontent,filename: test.dat"
      //      }
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

  def evaluateTo[T](value: T): Matcher[Future[Unmarshalling[T]]] =
    equal(value).matcher[T] compose { unmarshallingFuture ⇒
      Await.result(unmarshallingFuture, 1.second) match {
        case Unmarshalling.Success(x) ⇒ x
        case x                        ⇒ fail(x.toString)
      }
    }

  def haveParts[T <: MultipartParts](parts: BodyPart*): Matcher[Future[Unmarshalling[T]]] =
    equal(parts).matcher[Seq[BodyPart]] compose { unmarshallingFuture ⇒
      Await.result(unmarshallingFuture, 1.second) match {
        case Unmarshalling.Success(x) ⇒
          Await.result(Flow(x.parts).grouped(100).toFuture(materializer).recover {
            case _: NoSuchElementException ⇒ Nil
          }, 1.second)
        case x ⇒ fail(x.toString)
      }
    }

  def haveFormData(fields: (String, BodyPart)*): Matcher[Future[Unmarshalling[MultipartFormData]]] =
    equal(fields).matcher[Seq[(String, BodyPart)]] compose { unmarshallingFuture ⇒
      Await.result(unmarshallingFuture, 1.second) match {
        case Unmarshalling.Success(x) ⇒
          val partsSeq = Await.result(Flow(x.parts).grouped(100).toFuture(materializer).recover {
            case _: NoSuchElementException ⇒ Nil
          }, 1.second)
          partsSeq map { part ⇒
            part.headers.collectFirst {
              case `Content-Disposition`(ContentDispositionTypes.`form-data`, params) ⇒ params("name")
            }.get -> part
          }
        case x ⇒ fail(x.toString)
      }
    }
}
