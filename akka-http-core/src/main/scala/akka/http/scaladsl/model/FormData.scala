/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.model

/**
 * Simple model for `application/x-www-form-urlencoded` form data.
 */
final case class FormData(fields: Uri.Query)

object FormData {
  val Empty = FormData(Uri.Query.Empty)

  def apply(fields: Map[String, String]): FormData =
    if (fields.isEmpty) Empty else FormData(Uri.Query(fields))

  def apply(fields: (String, String)*): FormData =
    if (fields.isEmpty) Empty else FormData(Uri.Query(fields: _*))
}