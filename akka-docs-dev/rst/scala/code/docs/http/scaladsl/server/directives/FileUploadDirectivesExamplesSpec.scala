/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.http.scaladsl.server.directives

import java.io.File

import akka.http.scaladsl.model.{ MediaTypes, HttpEntity, Multipart, StatusCodes }
import akka.stream.io.Framing
import akka.util.ByteString
import docs.http.scaladsl.server.RoutingSpec

import scala.concurrent.Future
import scala.util.{ Success, Failure }

class FileUploadDirectivesExamplesSpec extends RoutingSpec {

  override def testConfigSource = "akka.actor.default-mailbox.mailbox-type = \"akka.dispatch.UnboundedMailbox\""

  "uploadedFile" in {

    val route =
      uploadedFile("csv") {
        case (metadata, file) =>
          // do something with the file and file metadata ...
          file.delete()
          complete(StatusCodes.OK)
      }

    // tests:
    val multipartForm =
      Multipart.FormData(
        Multipart.FormData.BodyPart.Strict(
          "csv",
          HttpEntity(MediaTypes.`text/plain`, "1,5,7\n11,13,17"),
          Map("filename" -> "data.csv")))

    Post("/", multipartForm) ~> route ~> check {
      status shouldEqual StatusCodes.OK
    }

  }

  "fileUpload" in {

    // adding integers as a service ;)
    val route =
      extractRequestContext { ctx =>
        implicit val mat = ctx.materializer
        implicit val ec = ctx.executionContext

        fileUpload("csv") {
          case (metadata, byteSource) =>

            val sumF: Future[Int] =
              // sum the numbers as they arrive so that we can
              // accept any size of file
              byteSource.via(Framing.delimiter(ByteString("\n"), 1024))
                .mapConcat(_.utf8String.split(",").toVector)
                .map(_.toInt)
                .runFold(0) { (acc, n) => acc + n }

            onSuccess(sumF) { sum => complete(s"Sum: $sum") }
        }
      }

    // tests:
    val multipartForm =
      Multipart.FormData(Multipart.FormData.BodyPart.Strict(
        "csv",
        HttpEntity(MediaTypes.`text/plain`, "2,3,5\n7,11,13,17,23\n29,31,37\n"),
        Map("filename" -> "primes.csv")))

    Post("/", multipartForm) ~> route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual "Sum: 178"
    }

  }

}
