/*
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.scaladsl.server.directives

import java.io.File

import akka.http.scaladsl.server.{ MissingFormFieldRejection, Directive1 }
import akka.http.scaladsl.model.{ ContentType, Multipart }
import akka.util.ByteString
import scala.concurrent.Future
import scala.util.{ Failure, Success }
import akka.stream.scaladsl._

trait FileUploadDirectives {

  import BasicDirectives._
  import RouteDirectives._
  import FutureDirectives._
  import MarshallingDirectives._

  /**
   * Streams the bytes of the file submitted using multipart with the given file name into a temporary file on disk.
   * If there is an error writing to disk the request will be failed with the thrown exception, if there is no such
   * field the request will be rejected, if there are multiple file parts with the same name, the first one will be
   * used and the subsequent ones ignored.
   */
  def uploadedFile(fieldName: String): Directive1[(FileInfo, File)] =
    extractRequestContext.flatMap { ctx ⇒
      import ctx.executionContext
      import ctx.materializer

      fileUpload(fieldName).flatMap {
        case (fileInfo, bytes) ⇒

          val destination = File.createTempFile("akka-http-upload", ".tmp")
          val uploadedF: Future[(FileInfo, File)] = bytes.runWith(FileIO.toFile(destination))
            .map(_ ⇒ (fileInfo, destination))

          onComplete[(FileInfo, File)](uploadedF).flatMap {

            case Success(uploaded) ⇒
              provide(uploaded)

            case Failure(ex) ⇒
              destination.delete()
              failWith(ex)

          }
      }
    }

  /**
   * Collects each body part that is a multipart file as a tuple containing metadata and a `Source`
   * for streaming the file contents somewhere. If there is no such field the request will be rejected,
   * if there are multiple file parts with the same name, the first one will be used and the subsequent
   * ones ignored.
   */
  def fileUpload(fieldName: String): Directive1[(FileInfo, Source[ByteString, Any])] =
    entity(as[Multipart.FormData]).flatMap { formData ⇒
      extractRequestContext.flatMap { ctx ⇒
        implicit val mat = ctx.materializer
        implicit val ec = ctx.executionContext

        val onePartSource: Source[(FileInfo, Source[ByteString, Any]), Any] = formData.parts
          .filter(part ⇒ part.filename.isDefined && part.name == fieldName)
          .map(part ⇒ (FileInfo(part.name, part.filename.get, part.entity.contentType), part.entity.dataBytes))
          .take(1)

        val onePartF = onePartSource.runWith(Sink.headOption[(FileInfo, Source[ByteString, Any])])

        onSuccess(onePartF)
      }

    }.flatMap {
      case Some(tuple) ⇒ provide(tuple)
      case None        ⇒ reject(MissingFormFieldRejection(fieldName))
    }
}

object FileUploadDirectives extends FileUploadDirectives

/**
 * Additional metadata about the file being uploaded/that was uploaded using the [[FileUploadDirectives]]
 *
 * @param fieldName Name of the form field the file was uploaded in
 * @param fileName User specified name of the uploaded file
 * @param contentType Content type of the file
 */
final case class FileInfo(fieldName: String, fileName: String, contentType: ContentType)
