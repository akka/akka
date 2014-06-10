/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import scala.collection.immutable
import headers._

sealed trait HttpForm {
  type FieldType
  def fields: Seq[FieldType]
}

/**
 * Model for `application/x-www-form-urlencoded` form data.
 */
final case class FormData(fields: Uri.Query) extends HttpForm {
  type FieldType = (String, String)
}

object FormData {
  val Empty = FormData(Uri.Query.Empty)
  def apply(fields: Map[String, String]): FormData = this(Uri.Query(fields))
}

/**
 * Model for `multipart/form-data` content as defined in RFC 2388.
 * All parts must contain a Content-Disposition header with a type form-data
 * and a name parameter that is unique
 */
final case class MultipartFormData(fields: immutable.Seq[BodyPart]) extends HttpForm {
  type FieldType = BodyPart
  def get(partName: String): Option[BodyPart] = fields.find(_.name.exists(_ == partName))
}

object MultipartFormData {
  val Empty = MultipartFormData()

  def apply(fields: BodyPart*): MultipartFormData = apply(immutable.Seq(fields: _*))

  def apply(fields: Map[String, BodyPart]): MultipartFormData = apply {
    fields.map {
      case (key, value) ⇒ value.copy(headers = `Content-Disposition`(ContentDispositionType.`form-data`, Map("name" -> key)) +: value.headers)
    }(collection.breakOut): _*
  }
}

final case class FormFile(name: Option[String], entity: HttpEntity.Default)

object FormFile {
  def apply(name: String, entity: HttpEntity.Default): FormFile = apply(Some(name), entity)
}
