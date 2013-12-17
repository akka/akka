package akka.http.model

import java.io.File
import headers.`Content-Disposition`

/**
 * Basic model for multipart content as defined in RFC 2046.
 * If you are looking for a model for `multipart/form-data` you should be using [[spray.http.MultipartFormData]].
 */
case class MultipartContent(parts: Seq[BodyPart])

object MultipartContent {
  val Empty = MultipartContent(Nil)

  def apply(files: Map[String, FormFile]): MultipartContent =
    MultipartContent(files.map(e ⇒ BodyPart(e._2, e._1))(collection.breakOut))
}

/**
 * Model for one part of a multipart message.
 */
case class BodyPart(entity: HttpEntity, headers: Seq[HttpHeader] = Nil) {
  val name: Option[String] = dispositionParameterValue("name")

  def filename: Option[String] = dispositionParameterValue("filename")
  def disposition: Option[String] =
    headers.collectFirst {
      case disposition: `Content-Disposition` ⇒ disposition.dispositionType
    }

  def dispositionParameterValue(parameter: String): Option[String] =
    headers.collectFirst {
      case `Content-Disposition`("form-data", parameters) if parameters.contains(parameter) ⇒
        parameters(parameter)
    }
}
object BodyPart {
  @deprecated("Use a BodyPart.apply overload instead", "1.0/1.1/1.2")
  def forFile(fieldName: String, file: FormFile): BodyPart =
    apply(file, fieldName)

  def apply(file: File, fieldName: String): BodyPart = apply(file, fieldName, ContentTypes.`application/octet-stream`)
  def apply(file: File, fieldName: String, contentType: ContentType): BodyPart =
    apply(HttpEntity(contentType, HttpData(file)), fieldName, Map.empty.updated("filename", file.getName))

  def apply(formFile: FormFile, fieldName: String): BodyPart =
    formFile.name match {
      case Some(name) ⇒ apply(formFile.entity, fieldName, Map.empty.updated("filename", name))
      case None       ⇒ apply(formFile.entity, fieldName)
    }

  def apply(entity: HttpEntity, fieldName: String): BodyPart = apply(entity, fieldName, Map.empty[String, String])
  def apply(entity: HttpEntity, fieldName: String, parameters: Map[String, String]): BodyPart =
    BodyPart(entity, Seq(`Content-Disposition`("form-data", parameters.updated("name", fieldName))))
}
