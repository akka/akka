package akka.http.model

import headers.`Content-Disposition`

sealed trait HttpForm {
  type FieldType
  def fields: Seq[FieldType]
}

/**
 * Model for `application/x-www-form-urlencoded` form data.
 */
case class FormData(fields: Seq[(String, String)]) extends HttpForm {
  type FieldType = (String, String)
}

object FormData {
  val Empty = FormData(Seq.empty)
  def apply(fields: Map[String, String]): FormData = this(fields.toSeq)
}

/**
 * Model for `multipart/form-data` content as defined in RFC 2388.
 * All parts must contain a Content-Disposition header with a type form-data
 * and a name parameter that is unique
 */
case class MultipartFormData(fields: Seq[BodyPart]) extends HttpForm { // TODO: `fields: BodyPart*` is probably better
  type FieldType = BodyPart
  def get(partName: String): Option[BodyPart] = fields.find(_.name.exists(_ == partName))
}

object MultipartFormData {
  val Empty = MultipartFormData(Seq.empty)
  def apply(fields: Map[String, BodyPart]): MultipartFormData = this{
    fields.map {
      case (key, value) ⇒ value.copy(headers = `Content-Disposition`("form-data", Map("name" -> key)) +: value.headers)
    }(collection.breakOut)
  }
}

case class FormFile(name: Option[String], entity: HttpEntity.NonEmpty)

object FormFile {
  def apply(name: String, entity: HttpEntity.NonEmpty): FormFile =
    apply(Some(name), entity)
}
