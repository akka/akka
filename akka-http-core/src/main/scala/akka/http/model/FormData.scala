/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

/**
 * Model for `application/x-www-form-urlencoded` form data.
 */
final case class FormData(fields: Uri.Query) {
  type FieldType = (String, String)
}

object FormData {
  val Empty = FormData(Uri.Query.Empty)
  def apply(fields: Map[String, String]): FormData =
    if (fields.isEmpty) Empty else FormData(Uri.Query(fields))
}