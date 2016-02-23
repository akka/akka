/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import java.io.{ FileInputStream, File }
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{ MissingFormFieldRejection, RoutingSpec }
import akka.util.ByteString

class FileUploadDirectivesSpec extends RoutingSpec {

  "the uploadedFile directive" should {

    "write a posted file to a temporary file on disk" in {

      val xml = "<int>42</int>"

      val simpleMultipartUpload =
        Multipart.FormData(Multipart.FormData.BodyPart.Strict(
          "fieldName",
          HttpEntity(ContentTypes.`text/xml(UTF-8)`, xml),
          Map("filename" -> "age.xml")))

      @volatile var file: Option[File] = None

      try {
        Post("/", simpleMultipartUpload) ~> {
          uploadedFile("fieldName") {
            case (info, tmpFile) ⇒
              file = Some(tmpFile)
              complete(info.toString)
          }
        } ~> check {
          file.isDefined === true
          responseAs[String] === FileInfo("fieldName", "age.xml", ContentTypes.`text/xml(UTF-8)`).toString
          read(file.get) === xml
        }
      } finally {
        file.foreach(_.delete())
      }
    }
  }

  "the fileUpload directive" should {

    def echoAsAService =
      extractRequestContext { ctx ⇒
        implicit val mat = ctx.materializer

        fileUpload("field1") {
          case (info, bytes) ⇒
            // stream the bytes somewhere
            val allBytesF = bytes.runFold(ByteString()) { (all, bytes) ⇒ all ++ bytes }

            // sum all individual file sizes
            onSuccess(allBytesF) { allBytes ⇒
              complete(allBytes)
            }
        }
      }

    "stream the file upload" in {
      // byte count as a service ;)
      val route = echoAsAService

      // tests:
      val str1 = "some data"
      val multipartForm =
        Multipart.FormData(Multipart.FormData.BodyPart.Strict(
          "field1",
          HttpEntity(ContentTypes.`text/plain(UTF-8)`, str1),
          Map("filename" -> "data1.txt")))

      Post("/", multipartForm) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual str1
      }

    }

    "stream the first file upload if multiple with the same name are posted" in {
      // byte count as a service ;)
      val route = echoAsAService

      // tests:
      val str1 = "some data"
      val str2 = "other data"
      val multipartForm =
        Multipart.FormData(
          Multipart.FormData.BodyPart.Strict(
            "field1",
            HttpEntity(ContentTypes.`text/plain(UTF-8)`, str1),
            Map("filename" -> "data1.txt")),
          Multipart.FormData.BodyPart.Strict(
            "field1",
            HttpEntity(ContentTypes.`text/plain(UTF-8)`, str2),
            Map("filename" -> "data2.txt")))

      Post("/", multipartForm) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual str1
      }

    }

    "reject the file upload if the field name is missing" in {
      // byte count as a service ;)
      val route =
        extractRequestContext { ctx ⇒
          implicit val mat = ctx.materializer

          fileUpload("missing") {
            case (info, bytes) ⇒
              // stream the bytes somewhere
              val allBytesF = bytes.runFold(ByteString()) { (all, bytes) ⇒ all ++ bytes }

              // sum all individual file sizes
              onSuccess(allBytesF) { allBytes ⇒
                complete(allBytes)
              }
          }
        }

      // tests:
      val str1 = "some data"
      val multipartForm =
        Multipart.FormData(Multipart.FormData.BodyPart.Strict(
          "field1",
          HttpEntity(ContentTypes.`text/plain(UTF-8)`, str1),
          Map("filename" -> "data1.txt")))

      Post("/", multipartForm) ~> route ~> check {
        rejection === MissingFormFieldRejection("missing")
      }

    }

  }

  private def read(file: File): String = {
    val in = new FileInputStream(file)
    try {
      val buffer = new Array[Byte](1024)
      in.read(buffer)
      new String(buffer, "UTF-8")
    } finally {
      in.close()
    }
  }

}
