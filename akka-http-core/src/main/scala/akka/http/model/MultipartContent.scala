/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import java.io.File
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Flow
import org.reactivestreams.Publisher
import akka.stream.impl.SynchronousPublisherFromIterable
import scala.collection.immutable
import headers._

import scala.concurrent.{ ExecutionContext, Future }

trait MultipartParts {
  def parts: Publisher[BodyPart]
}

/**
 * Basic model for multipart content as defined in RFC 2046.
 * If you are looking for a model for `multipart/form-data` you should be using [[MultipartFormData]].
 */
final case class MultipartContent(parts: Publisher[BodyPart]) extends MultipartParts

object MultipartContent {
  val Empty = MultipartContent(SynchronousPublisherFromIterable[BodyPart](Nil))

  def apply(parts: BodyPart*): MultipartContent = apply(SynchronousPublisherFromIterable[BodyPart](parts.toList))

  def apply(files: Map[String, FormFile]): MultipartContent =
    apply(files.map(e ⇒ BodyPart(e._2, e._1))(collection.breakOut): _*)
}

/**
 * Model for multipart/byteranges content as defined in RFC 2046.
 * If you are looking for a model for `multipart/form-data` you should be using [[MultipartFormData]].
 */
final case class MultipartByteRanges(parts: Publisher[BodyPart]) extends MultipartParts

object MultipartByteRanges {
  val Empty = MultipartByteRanges(SynchronousPublisherFromIterable[BodyPart](Nil))

  def apply(parts: BodyPart*): MultipartByteRanges =
    if (parts.isEmpty) Empty else MultipartByteRanges(SynchronousPublisherFromIterable[BodyPart](parts.toList))
}

/**
 * Model for `multipart/form-data` content as defined in RFC 2388.
 * All parts must contain a Content-Disposition header with a type form-data
 * and a name parameter that is unique.
 */
case class MultipartFormData(parts: Publisher[BodyPart]) extends MultipartParts {
  /**
   * Turns this instance into its strict specialization using the given `maxFieldCount` as the field number cut-off
   * hint.
   */
  def toStrict(materializer: FlowMaterializer, maxFieldCount: Int = 1000)(implicit ec: ExecutionContext): Future[StrictMultipartFormData] =
    Flow(parts).grouped(maxFieldCount).toFuture(materializer).map(new StrictMultipartFormData(_))
}

/**
 * A specialized `MultipartFormData` that allows full random access to its parts.
 */
class StrictMultipartFormData(val fields: immutable.Seq[BodyPart]) extends MultipartFormData(SynchronousPublisherFromIterable(fields)) {
  /**
   * Returns the BodyPart with the given name, if found.
   */
  def get(partName: String): Option[BodyPart] = fields.find(_.name.exists(_ == partName))

  override def toStrict(materializer: FlowMaterializer, maxFieldCount: Int)(implicit ec: ExecutionContext): Future[StrictMultipartFormData] =
    Future.successful(this)
}

object MultipartFormData {
  val Empty = MultipartFormData()

  def apply(parts: BodyPart*): MultipartFormData = apply(SynchronousPublisherFromIterable[BodyPart](parts.toList))

  def apply(fields: Map[String, BodyPart]): MultipartFormData = apply {
    fields.map {
      case (key, value) ⇒ value.copy(headers = `Content-Disposition`(ContentDispositionTypes.`form-data`, Map("name" -> key)) +: value.headers)
    }(collection.breakOut): _*
  }
}

final case class FormFile(name: Option[String], entity: HttpEntity)

object FormFile {
  def apply(name: String, entity: HttpEntity): FormFile = apply(Some(name), entity)
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

/**
 * A convenience extractor that allows to match on a BodyPart including its name if the body-part
 * is used as part of form-data. If the part has no name the extractor won't match.
 *
 * Example:
 *
 * {{{
 * (formData: StrictMultipartFormData).fields collect {
 *   case NamedBodyPart("address", data, headers) => data
 * }
 * }}}
 */
object NamedBodyPart {
  def unapply(part: BodyPart): Option[(String, HttpEntity, immutable.Seq[HttpHeader])] =
    part.name.map(name ⇒ (name, part.entity, part.headers))
}

/**
 * A convenience extractor that allows to match on a BodyPart including its name and filename
 * if the body-part is used as part of form-data. If the part has no name an empty string will be
 * extracted, instead. If the part has no filename the extractor won't match.
 *
 * Example:
 *
 * {{{
 * (formData: StrictMultipartFormData).fields collect {
 *   case FileBodyPart("file", filename, data, headers) => filename -> data
 * }
 * }}}
 */
object FileBodyPart {
  def unapply(part: BodyPart): Option[(String, String, HttpEntity, immutable.Seq[HttpHeader])] =
    part.filename.map(filename ⇒ (part.name.getOrElse(""), filename, part.entity, part.headers))
}
