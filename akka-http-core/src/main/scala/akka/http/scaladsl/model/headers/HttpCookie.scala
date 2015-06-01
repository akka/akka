/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.model.headers

import akka.parboiled2.CharPredicate
import akka.japi.{ Option ⇒ JOption }
import akka.http.scaladsl.model.DateTime
import akka.http.impl.util._
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.impl.util.JavaMapping.Implicits._

// see http://tools.ietf.org/html/rfc6265
final case class HttpCookiePair(
  name: String,
  value: String) extends jm.headers.HttpCookiePair with ToStringRenderable {

  HttpCookiePair.validate(name, value)

  def render[R <: Rendering](r: R): r.type = r ~~ name ~~ '=' ~~ value
  def toCookie: HttpCookie = HttpCookie.fromPair(this)
}
object HttpCookiePair {
  def apply(pair: (String, String)): HttpCookiePair = HttpCookiePair(pair._1, pair._2)

  private[http] def validate(name: String, value: String): Unit = {
    import HttpCookie._
    require(nameChars.matchesAll(name), s"'${nameChars.firstMismatch(name).get}' not allowed in cookie name ('$name')")
    require(valueChars.matchesAll(value), s"'${valueChars.firstMismatch(value).get}' not allowed in cookie content ('$value')")
  }
}

// see http://tools.ietf.org/html/rfc6265
final case class HttpCookie(
  name: String,
  value: String,
  expires: Option[DateTime] = None,
  maxAge: Option[Long] = None,
  domain: Option[String] = None,
  path: Option[String] = None,
  secure: Boolean = false,
  httpOnly: Boolean = false,
  extension: Option[String] = None) extends jm.headers.HttpCookie with ToStringRenderable {

  /** Returns the name/value pair for this cookie, to be used in [[Cookiie]] headers. */
  def pair: HttpCookiePair = HttpCookiePair(name, value)

  // TODO: suppress running these requires for cookies created from our header parser

  import HttpCookie._

  HttpCookiePair.validate(name, value)
  require(domain.forall(domainChars.matchesAll), s"'${domainChars.firstMismatch(domain.get).get}' not allowed in cookie domain ('${domain.get}')")
  require(path.forall(pathOrExtChars.matchesAll), s"'${pathOrExtChars.firstMismatch(path.get).get}' not allowed in cookie path ('${path.get}')")
  require(extension.forall(pathOrExtChars.matchesAll), s"'${pathOrExtChars.firstMismatch(extension.get).get}' not allowed in cookie extension ('${extension.get}')")

  def render[R <: Rendering](r: R): r.type = {
    r ~~ name ~~ '=' ~~ value
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
  def getExpires: JOption[jm.DateTime] = expires.asJava
}

object HttpCookie {
  def fromPair(pair: HttpCookiePair,
               expires: Option[DateTime] = None,
               maxAge: Option[Long] = None,
               domain: Option[String] = None,
               path: Option[String] = None,
               secure: Boolean = false,
               httpOnly: Boolean = false,
               extension: Option[String] = None): HttpCookie =
    HttpCookie(pair.name, pair.value, expires, maxAge, domain, path, secure, httpOnly, extension)

  import akka.http.impl.model.parser.CharacterClasses._

  private[http] def nameChars = tchar
  // http://tools.ietf.org/html/rfc6265#section-4.1.1
  // ; US-ASCII characters excluding CTLs, whitespace DQUOTE, comma, semicolon, and backslash
  private[http] val valueChars = CharPredicate('\u0021', '\u0023' to '\u002B', '\u002D' to '\u003A', '\u003C' to '\u005B', '\u005D' to '\u007E')
  private[http] val domainChars = ALPHANUM ++ ".-"
  private[http] val pathOrExtChars = VCHAR ++ ' ' -- ';'
}
