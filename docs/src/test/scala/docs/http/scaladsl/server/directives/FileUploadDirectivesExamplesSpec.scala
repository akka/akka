/*
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.directives.FileInfo
import akka.http.scaladsl.testkit.RouteTestTimeout
import akka.stream.scaladsl.Framing
import akka.testkit.TestDuration
import akka.util.ByteString
import docs.http.scaladsl.server.RoutingSpec
import java.io.File
import scala.concurrent.Future
import scala.concurrent.duration._

class FileUploadDirectivesExamplesSpec extends RoutingSpec {

  override def testConfigSource = "akka.actor.default-mailbox.mailbox-type = \"akka.dispatch.UnboundedMailbox\""

  // test touches disk, so give it some time
  implicit val routeTimeout = RouteTestTimeout(3.seconds.dilated)

  "uploadedFile" in {
    //#uploadedFile

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
          HttpEntity(ContentTypes.`text/plain(UTF-8)`, "2,3,5\n7,11,13,17,23\n29,31,37\n"),
          Map("filename" -> "primes.csv")))

    Post("/", multipartForm) ~> route ~> check {
      status shouldEqual StatusCodes.OK
    }

    //#uploadedFile
  }

  "storeUploadedFile" in {
    //#storeUploadedFile

    def tempDestination(fileInfo: FileInfo): File =
      File.createTempFile(fileInfo.fileName, ".tmp")

    val route =
      storeUploadedFile("csv", tempDestination) {
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
          HttpEntity(ContentTypes.`text/plain(UTF-8)`, "2,3,5\n7,11,13,17,23\n29,31,37\n"),
          Map("filename" -> "primes.csv")))

    Post("/", multipartForm) ~> route ~> check {
      status shouldEqual StatusCodes.OK
    }

    //#storeUploadedFile
  }

  "storeUploadedFiles" in {
    //#storeUploadedFiles

    def tempDestination(fileInfo: FileInfo): File =
      File.createTempFile(fileInfo.fileName, ".tmp")

    val route =
      storeUploadedFiles("csv", tempDestination) { files =>
        val finalStatus = files.foldLeft(StatusCodes.OK) {
          case (status, (metadata, file)) =>
            // do something with the file and file metadata ...
            file.delete()
            status
        }

        complete(finalStatus)
      }

    // tests:
    val multipartForm =
      Multipart.FormData(
        Multipart.FormData.BodyPart.Strict(
          "csv",
          HttpEntity(ContentTypes.`text/plain(UTF-8)`, "2,3,5\n7,11,13,17,23\n29,31,37\n"),
          Map("filename" -> "primesA.csv")),
        Multipart.FormData.BodyPart.Strict(
          "csv",
          HttpEntity(ContentTypes.`text/plain(UTF-8)`, "41,43,47\n53,59,6167,71\n73,79,83\n"),
          Map("filename" -> "primesB.csv")))

    Post("/", multipartForm) ~> route ~> check {
      status shouldEqual StatusCodes.OK
    }

    //#storeUploadedFiles
  }

  "fileUpload" in {
    //#fileUpload

    // adding integers as a service
    val route =
      extractRequestContext { ctx =>
        implicit val materializer = ctx.materializer

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
        HttpEntity(ContentTypes.`text/plain(UTF-8)`, "2,3,5\n7,11,13,17,23\n29,31,37\n"),
        Map("filename" -> "primes.csv")))

    Post("/", multipartForm) ~> route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual "Sum: 178"
    }

    //#fileUpload
  }

  "fileUploadAll" in {
    //#fileUploadAll

    // adding integers as a service
    val route =
      extractRequestContext { ctx =>
        implicit val materializer = ctx.materializer

        fileUploadAll("csv") {
          case byteSources =>
            // accumulate the sum of each file
            val sumF: Future[Int] = byteSources.foldLeft(Future.successful(0)) {
              case (accF, (metadata, byteSource)) =>
                // sum the numbers as they arrive
                val intF = byteSource.via(Framing.delimiter(ByteString("\n"), 1024))
                  .mapConcat(_.utf8String.split(",").toVector)
                  .map(_.toInt)
                  .runFold(0) { (acc, n) => acc + n }

                accF.flatMap(acc ⇒ intF.map(acc + _))
            }

            onSuccess(sumF) { sum => complete(s"Sum: $sum") }
        }
      }

    // tests:
    val multipartForm =
      Multipart.FormData(
        Multipart.FormData.BodyPart.Strict(
          "csv",
          HttpEntity(ContentTypes.`text/plain(UTF-8)`, "2,3,5\n7,11,13,17,23\n29,31,37\n"),
          Map("filename" -> "primesA.csv")),
        Multipart.FormData.BodyPart.Strict(
          "csv",
          HttpEntity(ContentTypes.`text/plain(UTF-8)`, "41,43,47\n53,59,61,67,71\n73,79,83\n"),
          Map("filename" -> "primesB.csv")))

    Post("/", multipartForm) ~> route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual "Sum: 855"
    }

    //#fileUploadAll
  }

}
