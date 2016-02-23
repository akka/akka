/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model

import akka.http.impl.model.parser.CharacterClasses
import akka.http.impl.util.StringRendering
import akka.http.scaladsl.model.MediaTypes._

/**
 * Simple model for `application/x-www-form-urlencoded` form data.
 */
final case class FormData(fields: Uri.Query) {
  def toEntity: akka.http.scaladsl.model.RequestEntity =
    toEntity(HttpCharsets.`UTF-8`)

  def toEntity(charset: HttpCharset): akka.http.scaladsl.model.RequestEntity = {
    val render: StringRendering = UriRendering.renderQuery(new StringRendering, this.fields, charset.nioCharset, CharacterClasses.unreserved)
    HttpEntity(`application/x-www-form-urlencoded` withCharset charset, render.get)
  }
}

object FormData {
  val Empty = FormData(Uri.Query.Empty)

  def apply(fields: Map[String, String]): FormData =
    if (fields.isEmpty) Empty else FormData(Uri.Query(fields))

  def apply(fields: (String, String)*): FormData =
    if (fields.isEmpty) Empty else FormData(Uri.Query(fields: _*))
}
