/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.scaladsl.server

import java.io.File

import akka.Done
import akka.actor.ActorRef
import akka.http.scaladsl.model.Multipart.FormData.BodyPart
import akka.stream.scaladsl.Framing
import akka.stream.scaladsl._
import akka.http.scaladsl.model.Multipart
import akka.util.ByteString

import scala.concurrent.duration._
import scala.concurrent.Future

class FileUploadExamplesSpec extends RoutingSpec {

  case class Video(file: File, title: String, author: String)
  object db {
    def create(video: Video): Future[Unit] = Future.successful(Unit)
  }

  "simple-upload" in {
    val uploadVideo =
      path("video") {
        entity(as[Multipart.FormData]) { formData =>

          // collect all parts of the multipart as it arrives into a map
          val allPartsF: Future[Map[String, Any]] = formData.parts.mapAsync[(String, Any)](1) {

            case b: BodyPart if b.name == "file" =>
              // stream into a file as the chunks of it arrives and return a future
              // file to where it got stored
              val file = File.createTempFile("upload", "tmp")
              b.entity.dataBytes.runWith(FileIO.toFile(file)).map(_ =>
                (b.name -> file))

            case b: BodyPart =>
              // collect form field values
              b.toStrict(2.seconds).map(strict =>
                (b.name -> strict.entity.data.utf8String))

          }.runFold(Map.empty[String, Any])((map, tuple) => map + tuple)

          val done = allPartsF.map { allParts =>
            // You would have some better validation/unmarshalling here
            db.create(Video(
              file = allParts("file").asInstanceOf[File],
              title = allParts("title").asInstanceOf[String],
              author = allParts("author").asInstanceOf[String]))
          }

          // when processing have finished create a response for the user
          onSuccess(allPartsF) { allParts =>
            complete {
              "ok!"
            }
          }
        }
      }
  }

  object MetadataActor {
    case class Entry(id: Long, values: Seq[String])
  }
  val metadataActor: ActorRef = system.deadLetters

  "stream-csv-upload" in {
    val splitLines = Framing.delimiter(ByteString("\n"), 256)

    val csvUploads =
      path("metadata" / LongNumber) { id =>
        entity(as[Multipart.FormData]) { formData =>
          val done: Future[Done] = formData.parts.mapAsync(1) {
            case b: BodyPart if b.filename.exists(_.endsWith(".csv")) =>
              b.entity.dataBytes
                .via(splitLines)
                .map(_.utf8String.split(",").toVector)
                .runForeach(csv =>
                  metadataActor ! MetadataActor.Entry(id, csv))
            case _ => Future.successful(Done)
          }.runWith(Sink.ignore)

          // when processing have finished create a response for the user
          onSuccess(done) { _ =>
            complete {
              "ok!"
            }
          }
        }
      }
  }

}
