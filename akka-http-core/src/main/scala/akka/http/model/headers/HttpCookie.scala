package akka.http.model
package headers

import akka.http.util._

// see http://tools.ietf.org/html/rfc6265
case class HttpCookie(
  name: String,
  content: String,
  expires: Option[DateTime] = None,
  maxAge: Option[Long] = None,
  domain: Option[String] = None,
  path: Option[String] = None,
  secure: Boolean = false,
  httpOnly: Boolean = false,
  extension: Option[String] = None) extends ValueRenderable {

  import HttpCookie._

  // TODO: suppress running these requires for cookies created from our header parser
  require(nameChars.matchAll(name), s"'${nameChars.firstMismatch(name).get}' not allowed in cookie name ('$name')")
  require(contentChars.matchAll(content), s"'${contentChars.firstMismatch(content).get}' not allowed in cookie content ('$content')")
  require(domain.isEmpty || domainChars.matchAll(domain.get), s"'${domainChars.firstMismatch(domain.get).get}' not allowed in cookie domain ('${domain.get}')")
  require(path.isEmpty || pathOrExtChars.matchAll(path.get), s"'${pathOrExtChars.firstMismatch(path.get).get}' not allowed in cookie path ('${path.get}')")
  require(extension.isEmpty || pathOrExtChars.matchAll(extension.get), s"'${pathOrExtChars.firstMismatch(extension.get).get}' not allowed in cookie extension ('${extension.get}')")

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
}

object HttpCookie {
  import CharPredicate._

  def nameChars = HttpToken
  // http://tools.ietf.org/html/rfc6265#section-4.1.1
  // ; US-ASCII characters excluding CTLs, whitespace DQUOTE, comma, semicolon, and backslash
  val contentChars = CharPredicate('\u0021', '\u0023' to '\u002B', '\u002D' to '\u003A', '\u003C' to '\u005B', '\u005D' to '\u007E')
  val domainChars = AlphaNum ++ ".-"
  val pathOrExtChars = Printable -- ';'
}
