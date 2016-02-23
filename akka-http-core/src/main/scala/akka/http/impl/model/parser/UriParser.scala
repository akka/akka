/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.model.parser

import java.nio.charset.Charset
import akka.parboiled2._
import akka.http.impl.util.enhanceString_
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers.HttpOrigin
import Parser.DeliveryScheme.Either
import Uri._

// format: OFF

// http://tools.ietf.org/html/rfc3986
private[http] class UriParser(val input: ParserInput,
                              val uriParsingCharset: Charset = UTF8,
                              val uriParsingMode: Uri.ParsingMode = Uri.ParsingMode.Relaxed) extends Parser
  with IpAddressParsing with StringBuilding {
  import CharacterClasses._

  def parseAbsoluteUri(): Uri =
    rule(`absolute-URI` ~ EOI).run() match {
      case Right(_) => create(_scheme, _userinfo, _host, _port, collapseDotSegments(_path), _rawQueryString, _fragment)
      case Left(error) => fail(error, "absolute URI")
    }

  def parseUriReference(): Uri =
    rule(`URI-reference` ~ EOI).run() match {
      case Right(_) => createUriReference()
      case Left(error) => fail(error, "URI reference")
    }

  def parseAndResolveUriReference(base: Uri): Uri =
    rule(`URI-reference` ~ EOI).run() match {
      case Right(_) => resolve(_scheme, _userinfo, _host, _port, _path, _rawQueryString, _fragment, base)
      case Left(error) => fail(error, "URI reference")
    }

  def parseOrigin(): HttpOrigin =
    rule(origin ~ EOI).run() match {
      case Right(_) => HttpOrigin(_scheme, akka.http.scaladsl.model.headers.Host(_host.address, _port))
      case Left(error) => fail(error, "origin")
    }

  def parseHost(): Host =
    rule(relaxedHost ~ EOI).run() match {
      case Right(_) => _host
      case Left(error) => fail(error, "URI host")
    }

  def parseQuery(): Query =
    rule(query ~ EOI).run() match {
      case Right(query) => query
      case Left(error) => fail(error, "query")
    }

  def fail(error: ParseError, target: String): Nothing = {
    val formatter = new ErrorFormatter(showLine = false)
    Uri.fail(s"Illegal $target: " + formatter.format(error, input), formatter.formatErrorLine(error, input))
  }

  private[this] val `path-segment-char` = uriParsingMode match {
    case Uri.ParsingMode.Strict ⇒ `pchar-base`
    case _                      ⇒ `relaxed-path-segment-char`
  }
  private[this] val `query-char` = uriParsingMode match {
    case Uri.ParsingMode.Strict              ⇒ `strict-query-char`
    case Uri.ParsingMode.Relaxed             ⇒ `relaxed-query-char`
  }
  private[this] val `fragment-char` = uriParsingMode match {
    case Uri.ParsingMode.Strict ⇒ `query-fragment-char`
    case _                      ⇒ `relaxed-fragment-char`
  }

  var _scheme = ""
  var _userinfo = ""
  var _host: Host = Host.Empty
  var _port: Int = 0
  var _path: Path = Path.Empty
  var _rawQueryString: Option[String] = None
  var _fragment: Option[String] = None

  // http://tools.ietf.org/html/rfc3986#appendix-A

  def URI = rule { scheme ~ ':' ~ `hier-part` ~ optional('?' ~ rawQueryString) ~ optional('#' ~ fragment) }

  def origin = rule { scheme ~ ':' ~ '/' ~ '/' ~ hostAndPort }

  def `hier-part` = rule(
    '/' ~ '/' ~ authority ~ `path-abempty`
    | `path-absolute`
    | `path-rootless`
    | `path-empty`)

  def `URI-reference` = rule { URI | `relative-ref` }

  def `URI-reference-pushed`: Rule1[Uri] = rule { `URI-reference` ~ push(createUriReference()) }

  def `absolute-URI` = rule { scheme ~ ':' ~ `hier-part` ~ optional('?' ~ rawQueryString) }

  def `relative-ref` = rule { `relative-part` ~ optional('?' ~ rawQueryString) ~ optional('#' ~ fragment) }

  def `relative-part` = rule(
    '/' ~ '/' ~ authority ~ `path-abempty`
    | `path-absolute`
    | `path-noscheme`
    | `path-empty`)

  def scheme = rule(
    'h' ~ 't' ~ 't' ~ 'p' ~ (&(':') ~ run(_scheme = "http") | 's' ~ &(':') ~ run(_scheme = "https"))
    | clearSB() ~ ALPHA ~ appendLowered() ~ zeroOrMore(`scheme-char` ~ appendLowered()) ~ &(':') ~ run(_scheme = sb.toString))

  def authority = rule { optional(userinfo) ~ hostAndPort }

  def userinfo = rule {
    clearSB() ~ zeroOrMore(`userinfo-char` ~ appendSB()| `pct-encoded`) ~ '@' ~ run(_userinfo = sb.toString)
  }

  def hostAndPort = rule { host ~ optional(':' ~ port)  }

  def `hostAndPort-pushed` = rule { hostAndPort ~ push(_host) ~ push(_port) }

  def host = rule { `IP-literal` | ipv4Host | `reg-name` }

  /** A relaxed host rule to use in `parseHost` that also recognizes IPv6 address without the brackets. */
  def relaxedHost = rule { `IP-literal` | ipv6Host | ipv4Host | `reg-name` }

  def port = rule {
    DIGIT ~ run(_port = lastChar - '0') ~ optional(
      DIGIT ~ run(_port = 10 * _port + lastChar - '0') ~ optional(
        DIGIT ~ run(_port = 10 * _port + lastChar - '0') ~ optional(
          DIGIT ~ run(_port = 10 * _port + lastChar - '0') ~ optional(
            DIGIT ~ run(_port = 10 * _port + lastChar - '0')))))
  }

  def `IP-literal` = rule { '[' ~ ipv6Host ~ ']' } // IPvFuture not currently recognized

  def ipv4Host = rule { capture(`ip-v4-address`) ~ &(colonSlashEOI) ~> ((b, a) => _host = IPv4Host(b, a)) }
  def ipv6Host = rule { capture(`ip-v6-address`) ~> ((b, a) => _host = IPv6Host(b, a)) }

  def `reg-name` = rule(
    clearSBForDecoding() ~ oneOrMore(`lower-reg-name-char` ~ appendSB() | UPPER_ALPHA ~ appendLowered() | `pct-encoded`) ~
      run(_host = NamedHost(getDecodedStringAndLowerIfEncoded(UTF8)))
    | run(_host = Host.Empty))

  def `path-abempty`  = rule { clearSB() ~ slashSegments ~ savePath() }
  def `path-absolute` = rule { clearSB() ~ '/' ~ appendSB('/') ~ optional(`segment-nz` ~ slashSegments) ~ savePath() }
  def `path-noscheme` = rule { clearSB() ~ `segment-nz-nc` ~ slashSegments ~ savePath() }
  def `path-rootless` = rule { clearSB() ~ `segment-nz` ~ slashSegments ~ savePath() }
  def `path-empty` = rule { MATCH }

  def slashSegments = rule { zeroOrMore('/' ~ appendSB('/') ~ segment) }

  def segment = rule { zeroOrMore(pchar) }
  def `segment-nz` = rule { oneOrMore(pchar) }
  def `segment-nz-nc` = rule { oneOrMore(!':' ~ pchar) }

  def pchar = rule { `path-segment-char` ~ appendSB() | `pct-encoded` }

  def rawQueryString = rule {
    clearSB() ~ oneOrMore(`raw-query-char` ~ appendSB()) ~ run(_rawQueryString = Some(sb.toString)) | run(_rawQueryString = Some(""))
  }

  // http://www.w3.org/TR/html401/interact/forms.html#h-17.13.4.1
  def query: Rule1[Query] = {
    def part = rule(
      clearSBForDecoding() ~ oneOrMore('+' ~ appendSB(' ') | `query-char` ~ appendSB() | `pct-encoded`) ~ push(getDecodedString())
        | push(""))

    // non-tail recursion, which we accept because it allows us to directly build the query
    // without having to reverse it at the end.
    // Also: request queries usually do not have hundreds of elements, so we should get away with
    // putting some pressure onto the JVM and value stack
    def keyValuePairs: Rule1[Query] = rule {
      part ~ ('=' ~ part | push(Query.EmptyValue)) ~ ('&' ~ keyValuePairs | push(Query.Empty)) ~> { (key, value, tail) =>
        Query.Cons(key, value, tail)
      }
    }

    rule { keyValuePairs }
  }

  def fragment = rule(
    clearSBForDecoding() ~ oneOrMore(`fragment-char` ~ appendSB() | `pct-encoded`) ~ run(_fragment = Some(getDecodedString()))
    | run(_fragment = Some("")))

  def `pct-encoded` = rule {
    '%' ~ HEXDIG ~ HEXDIG ~ run {
      if (firstPercentIx == -1) firstPercentIx = sb.length()
      sb.append('%').append(charAt(-2)).append(lastChar)
    }
  }

  //////////////////////////// ADDITIONAL HTTP-SPECIFIC RULES //////////////////////////

  // http://tools.ietf.org/html/rfc7230#section-2.7
  def `absolute-path` = rule {
    clearSB() ~ oneOrMore('/' ~ appendSB('/') ~ segment) ~ savePath()
  }

  // http://tools.ietf.org/html/rfc7230#section-5.3
  def `request-target` = rule(
    `absolute-path` ~ optional('?' ~ rawQueryString) // origin-form
      | `absolute-URI` // absolute-form
      | authority) // authority-form or asterisk-form

  def parseHttpRequestTarget(): Uri =
    rule(`request-target` ~ EOI).run() match {
      case Right(_) =>
        val path = if (_scheme.isEmpty) _path else collapseDotSegments(_path)
        create(_scheme, _userinfo, _host, _port, path, _rawQueryString, _fragment)
      case Left(error) => fail(error, "request-target")
    }

  ///////////// helpers /////////////

  private def appendLowered(): Rule0 = rule { run(sb.append(CharUtils.toLowerCase(lastChar))) }

  private def savePath() = rule { run(_path = Path(sb.toString, uriParsingCharset)) }

  private[this] var firstPercentIx = -1

  private def clearSBForDecoding(): Rule0 = rule { run { sb.setLength(0); firstPercentIx = -1 } }

  private def getDecodedString(charset: Charset = uriParsingCharset) =
    if (firstPercentIx >= 0) decode(sb.toString, charset, firstPercentIx)() else sb.toString

  private def getDecodedStringAndLowerIfEncoded(charset: Charset) =
    if (firstPercentIx >= 0) decode(sb.toString, charset, firstPercentIx)().toRootLowerCase else sb.toString

  private def createUriReference(): Uri = {
    val path = if (_scheme.isEmpty) _path else collapseDotSegments(_path)
    create(_scheme, _userinfo, _host, _port, path, _rawQueryString, _fragment)
  }
}

