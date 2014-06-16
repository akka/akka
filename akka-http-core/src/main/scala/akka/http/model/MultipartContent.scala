/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import java.io.File
import org.reactivestreams.api.Producer
import akka.stream.impl.SynchronousProducerFromIterable
import scala.collection.immutable
import headers._

trait MultipartParts {
  def parts: Producer[BodyPart]
}

/**
 * Basic model for multipart content as defined in RFC 2046.
 * If you are looking for a model for `multipart/form-data` you should be using [[MultipartFormData]].
 */
final case class MultipartContent(parts: Producer[BodyPart]) extends MultipartParts

object MultipartContent {
  val Empty = MultipartContent(SynchronousProducerFromIterable[BodyPart](Nil))

  def apply(parts: BodyPart*): MultipartContent = apply(SynchronousProducerFromIterable[BodyPart](parts.toList))

  def apply(files: Map[String, FormFile]): MultipartContent =
    apply(files.map(e ⇒ BodyPart(e._2, e._1))(collection.breakOut): _*)
}

/**
 * Model for multipart/byteranges content as defined in RFC 2046.
 * If you are looking for a model for `multipart/form-data` you should be using [[MultipartFormData]].
 */
final case class MultipartByteRanges(parts: Producer[BodyPart]) extends MultipartParts

object MultipartByteRanges {
  val Empty = MultipartByteRanges(SynchronousProducerFromIterable[BodyPart](Nil))

  def apply(parts: BodyPart*): MultipartByteRanges = apply(SynchronousProducerFromIterable[BodyPart](parts.toList))
}

/**
 * Model for one part of a multipart message.
 */
final case class BodyPart(entity: HttpEntity, headers: immutable.Seq[HttpHeader] = Nil) {
  val name: Option[String] = dispositionParameterValue("name")

  def filename: Option[String] = dispositionParameterValue("filename")
  def dispositionType: Option[ContentDispositionType] =
    headers.collectFirst {
      case `Content-Disposition`(dispositionType, _) ⇒ dispositionType
    }

  def dispositionParameterValue(parameter: String): Option[String] =
    headers.collectFirst {
      case `Content-Disposition`(ContentDispositionTypes.`form-data`, params) if params.contains(parameter) ⇒
        params(parameter)
    }

  def contentRange: Option[ContentRange] =
    headers.collectFirst {
      case `Content-Range`(_, contentRange) ⇒ contentRange
    }
}

object BodyPart {
  def apply(file: File, fieldName: String): BodyPart = apply(file, fieldName, ContentTypes.`application/octet-stream`)
  def apply(file: File, fieldName: String, contentType: ContentType): BodyPart =
    apply(HttpEntity(contentType, file), fieldName, Map.empty.updated("filename", file.getName))

  def apply(formFile: FormFile, fieldName: String): BodyPart =
    formFile.name match {
      case Some(name) ⇒ apply(formFile.entity, fieldName, Map.empty.updated("filename", name))
      case None       ⇒ apply(formFile.entity, fieldName)
    }

  def apply(entity: HttpEntity, fieldName: String): BodyPart = apply(entity, fieldName, Map.empty[String, String])
  def apply(entity: HttpEntity, fieldName: String, params: Map[String, String]): BodyPart =
    BodyPart(entity, immutable.Seq(`Content-Disposition`(ContentDispositionTypes.`form-data`, params.updated("name", fieldName))))
}