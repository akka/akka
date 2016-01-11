/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.model

import akka.http.impl.model.parser.CharacterClasses
import akka.http.impl.util.StringRendering
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.scaladsl.model.HttpCharsets._
import akka.http.scaladsl.model.MediaTypes._

/**
 * Simple model for `application/x-www-form-urlencoded` form data.
 */
final case class FormData(fields: Uri.Query) extends jm.FormData {
  override def toEntity: akka.http.scaladsl.model.RequestEntity =
    toEntity(HttpCharsets.`UTF-8`)

  def toEntity(charset: HttpCharset): akka.http.scaladsl.model.RequestEntity = {
    // TODO this logic is duplicated in javadsl.model.FormData, spent hours trying to DRY it but compiler freaked out in a number of ways... -- ktoso
    val render: StringRendering = UriRendering.renderQuery(new StringRendering, this.fields, charset.nioCharset, CharacterClasses.unreserved)
    HttpEntity(ContentType(`application/x-www-form-urlencoded`, `UTF-8`), render.get)
  }
}

object FormData {
  val Empty = FormData(Uri.Query.Empty)

  def apply(fields: Map[String, String]): FormData =
    if (fields.isEmpty) Empty else FormData(Uri.Query(fields))

  def apply(fields: (String, String)*): FormData =
    if (fields.isEmpty) Empty else FormData(Uri.Query(fields: _*))

  def create(fields: Array[akka.japi.Pair[String, String]]): FormData =
    if (fields.isEmpty) Empty else FormData(Uri.Query(fields.map(_.toScala): _*))
}
