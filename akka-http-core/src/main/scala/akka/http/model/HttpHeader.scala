/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import akka.http.util.ToStringRenderable

/**
 * The model of an HTTP header. In its most basic form headers are simple name-value pairs. Header names
 * are compared in a case-insensitive way.
 */
abstract class HttpHeader extends japi.HttpHeader with ToStringRenderable {
  def name: String
  def value: String
  def lowercaseName: String
  def is(nameInLowerCase: String): Boolean = lowercaseName == nameInLowerCase
  def isNot(nameInLowerCase: String): Boolean = lowercaseName != nameInLowerCase
}

object HttpHeader {
  /**
   * Extract name and value from a header.
   * CAUTION: The name must be matched in *all-lowercase*!.
   */
  def unapply(header: HttpHeader): Option[(String, String)] = Some((header.lowercaseName, header.value))
}
