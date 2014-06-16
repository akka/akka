/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package headers

import akka.parboiled2.CharPredicate
import akka.http.util._
import akka.japi.{ Option ⇒ JOption }

import akka.http.model.japi.JavaMapping.Implicits._

// see http://tools.ietf.org/html/rfc6265
final case class HttpCookie(
  name: String,
  content: String,
  expires: Option[DateTime] = None,
  maxAge: Option[Long] = None,
  domain: Option[String] = None,
  path: Option[String] = None,
  secure: Boolean = false,
  httpOnly: Boolean = false,
  extension: Option[String] = None) extends japi.headers.HttpCookie with ValueRenderable {

  import HttpCookie._

  // TODO: suppress running these requires for cookies created from our header parser
  require(nameChars.matchesAll(name), s"'${nameChars.firstMismatch(name).get}' not allowed in cookie name ('$name')")
  require(contentChars.matchesAll(content), s"'${contentChars.firstMismatch(content).get}' not allowed in cookie content ('$content')")
  require(domain.isEmpty || domainChars.matchesAll(domain.get), s"'${domainChars.firstMismatch(domain.get).get}' not allowed in cookie domain ('${domain.get}')")
  require(path.isEmpty || pathOrExtChars.matchesAll(path.get), s"'${pathOrExtChars.firstMismatch(path.get).get}' not allowed in cookie path ('${path.get}')")
  require(extension.isEmpty || pathOrExtChars.matchesAll(extension.get), s"'${pathOrExtChars.firstMismatch(extension.get).get}' not allowed in cookie extension ('${extension.get}')")

  def render[R <: Rendering](r: R): r.type = {
    r ~~ name ~~ '=' ~~ content
    if (expires.isDefined) expires.get.renderRfc1123DateTimeString(r ~~ "; Expires=")
    if (maxAge.isDefined) r ~~ "; Max-Age=" ~~ maxAge.get
    if (domain.isDefined) r ~~ "; Domain=" ~~ domain.get
    if (path.isDefined) r ~~ "; Path=" ~~ path.get
    if (secure) r ~~ "; Secure"
    if (httpOnly) r ~~ "; HttpOnly"
    if (extension.isDefined) r ~~ ';' ~~ ' ' ~~ extension.get
    r
  }

  /** Java API */
  def getExtension: JOption[String] = extension.asJava
  /** Java API */
  def getPath: JOption[String] = path.asJava
  /** Java API */
  def getDomain: JOption[String] = domain.asJava
  /** Java API */
  def getMaxAge: JOption[java.lang.Long] = maxAge.asJava
  /** Java API */
  def getExpires: JOption[japi.DateTime] = expires.asJava
}

object HttpCookie {
  import akka.http.model.parser.CharacterClasses._

  def nameChars = tchar
  // http://tools.ietf.org/html/rfc6265#section-4.1.1
  // ; US-ASCII characters excluding CTLs, whitespace DQUOTE, comma, semicolon, and backslash
  val contentChars = CharPredicate('\u0021', '\u0023' to '\u002B', '\u002D' to '\u003A', '\u003C' to '\u005B', '\u005D' to '\u007E')
  val domainChars = ALPHANUM ++ ".-"
  val pathOrExtChars = VCHAR ++ ' ' -- ';'
}
