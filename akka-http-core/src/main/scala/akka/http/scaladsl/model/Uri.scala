/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model

import language.implicitConversions
import java.net.{ Inet4Address, Inet6Address, InetAddress }
import java.lang.{ StringBuilder ⇒ JStringBuilder, Iterable }
import java.nio.charset.Charset
import scala.annotation.tailrec
import scala.collection.{ immutable, mutable, LinearSeqOptimized }
import scala.collection.immutable.LinearSeq
import akka.parboiled2.{ CharUtils, CharPredicate, ParserInput }
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.impl.model.parser.UriParser
import akka.http.impl.model.parser.CharacterClasses._
import akka.http.impl.util._
import Uri._

/**
 * An immutable model of an internet URI as defined by http://tools.ietf.org/html/rfc3986.
 * All members of this class represent the *decoded* URI elements (i.e. without percent-encoding).
 */
sealed abstract case class Uri(scheme: String, authority: Authority, path: Path, rawQueryString: Option[String],
                               fragment: Option[String]) {

  def isAbsolute: Boolean = !isRelative
  def isRelative: Boolean = scheme.isEmpty
  def isEmpty: Boolean

  /**
   * Parses the rawQueryString member into a Query instance.
   */
  def query(charset: Charset = UTF8, mode: Uri.ParsingMode = Uri.ParsingMode.Relaxed): Query = rawQueryString match {
    case Some(q) ⇒ new UriParser(q, charset, mode).parseQuery()
    case None    ⇒ Query.Empty
  }

  /**
   * Returns the query part of the Uri in its decoded form.
   */
  def queryString(charset: Charset = UTF8): Option[String] = rawQueryString.map(s ⇒ decode(s, charset))

  /**
   * The effective port of this Uri given the currently set authority and scheme values.
   * If the authority has an explicitly set port (i.e. a non-zero port value) then this port
   * is the effective port. Otherwise the default port for the current scheme is returned.
   */
  def effectivePort: Int = if (authority.port != 0) authority.port else defaultPorts(scheme)

  /**
   * Returns a copy of this Uri with the given components.
   */
  def copy(scheme: String = scheme, authority: Authority = authority, path: Path = path,
           rawQueryString: Option[String] = rawQueryString, fragment: Option[String] = fragment): Uri =
    Uri(scheme, authority, path, rawQueryString, fragment)

  /**
   * Returns a copy of this Uri with the given scheme. The `scheme` change of the Uri has the following
   * effect on the port value:
   *  - If the Uri has a non-default port for the scheme before the change this port will remain unchanged.
   *  - If the Uri has the default port for the scheme before the change it will have the default port for
   *    the '''new''' scheme after the change.
   */
  def withScheme(scheme: String): Uri = copy(scheme = scheme)

  /**
   * Returns a copy of this Uri with the given authority.
   */
  def withAuthority(authority: Authority): Uri = copy(authority = authority)

  /**
   * Returns a copy of this Uri with a Authority created using the given host, port and userinfo.
   */
  def withAuthority(host: Host, port: Int, userinfo: String = ""): Uri = copy(authority = Authority(host, port, userinfo))

  /**
   * Returns a copy of this Uri with a Authority created using the given host and port.
   */
  def withAuthority(host: String, port: Int): Uri = copy(authority = Authority(Host(host), port))

  /**
   * Returns a copy of this Uri with the given host.
   */
  def withHost(host: Host): Uri = copy(authority = authority.copy(host = host))

  /**
   * Returns a copy of this Uri with the given host.
   */
  def withHost(host: String): Uri = copy(authority = authority.copy(host = Host(host)))

  /**
   * Returns a copy of this Uri with the given port.
   */
  def withPort(port: Int): Uri = copy(authority = authority.copy(port = port))

  /**
   * Returns a copy of this Uri with the given path.
   */
  def withPath(path: Path): Uri = copy(path = path)

  /**
   * Returns a copy of this Uri with the given userinfo.
   */
  def withUserInfo(userinfo: String): Uri = copy(authority = authority.copy(userinfo = userinfo))

  /**
   * Returns a copy of this Uri with the given query.
   */
  def withQuery(query: Query): Uri = copy(rawQueryString = if (query.isEmpty) None else Some(query.toString))

  /**
   * Returns a copy of this Uri with a Query created using the given query string.
   */
  def withRawQueryString(rawQuery: String): Uri = copy(rawQueryString = Some(rawQuery))

  /**
   * Returns a copy of this Uri with the given fragment.
   */
  def withFragment(fragment: String): Uri = copy(fragment = fragment.toOption)

  /**
   * Returns a new absolute Uri that is the result of the resolution process defined by
   * http://tools.ietf.org/html/rfc3986#section-5.2.2
   * The given base Uri must be absolute.
   */
  def resolvedAgainst(base: Uri): Uri =
    resolve(scheme, authority.userinfo, authority.host, authority.port, path, rawQueryString, fragment, base)

  /**
   * Converts this URI to an "effective HTTP request URI" as defined by
   * http://tools.ietf.org/html/rfc7230#section-5.5
   */
  def toEffectiveHttpRequestUri(hostHeaderHost: Host, hostHeaderPort: Int, securedConnection: Boolean = false,
                                defaultAuthority: Authority = Authority.Empty): Uri =
    effectiveHttpRequestUri(scheme, authority.host, authority.port, path, rawQueryString, fragment, securedConnection,
      hostHeaderHost, hostHeaderPort, defaultAuthority)

  /**
   * Converts this URI into a relative URI by keeping the path, query and fragment, but dropping the scheme and authority.
   */
  def toRelative =
    Uri(path = if (path.isEmpty) Uri.Path./ else path, queryString = rawQueryString, fragment = fragment)

  /**
   * Converts this URI into an HTTP request target "origin-form" as defined by
   * https://tools.ietf.org/html/rfc7230#section-5.3.
   *
   * Note that the resulting URI instance is not a valid RFC 3986 URI! (As it might
   * be a "relative" URI with a part component starting with a double slash.)
   */
  def toHttpRequestTargetOriginForm =
    create("", Authority.Empty, if (path.isEmpty) Uri.Path./ else path, rawQueryString, None)

  /**
   * Drops the fragment from this URI
   */
  def withoutFragment =
    copy(fragment = None)

  override def toString = UriRendering.UriRenderer.render(new StringRendering, this).get
}

object Uri {
  object Empty extends Uri("", Authority.Empty, Path.Empty, None, None) {
    def isEmpty = true
  }

  val / : Uri = "/"

  /**
   * Parses a valid URI string into a normalized URI reference as defined
   * by http://tools.ietf.org/html/rfc3986#section-4.1.
   * Percent-encoded octets are UTF-8 decoded.
   * Accepts unencoded visible 7-bit ASCII characters in addition to the RFC.
   * If the given string is not a valid URI the method throws an `IllegalUriException`.
   */
  implicit def apply(input: String): Uri = apply(input: ParserInput, UTF8, Uri.ParsingMode.Relaxed)

  /**
   * Parses a valid URI string into a normalized URI reference as defined
   * by http://tools.ietf.org/html/rfc3986#section-4.1.
   * Percent-encoded octets are decoded using the given charset (where specified by the RFC).
   * Accepts unencoded visible 7-bit ASCII characters in addition to the rfc.
   * If the given string is not a valid URI the method throws an `IllegalUriException`.
   */
  def apply(input: ParserInput): Uri = apply(input, UTF8, Uri.ParsingMode.Relaxed)

  /**
   * Parses a valid URI string into a normalized URI reference as defined
   * by http://tools.ietf.org/html/rfc3986#section-4.1.
   * Percent-encoded octets are decoded using the given charset (where specified by the RFC).
   * If strict is `false`, accepts unencoded visible 7-bit ASCII characters in addition to the RFC.
   * If the given string is not a valid URI the method throws an `IllegalUriException`.
   */
  def apply(input: ParserInput, mode: Uri.ParsingMode): Uri = apply(input, UTF8, mode)

  /**
   * Parses a valid URI string into a normalized URI reference as defined
   * by http://tools.ietf.org/html/rfc3986#section-4.1.
   * Percent-encoded octets are decoded using the given charset (where specified by the RFC).
   * If strict is `false`, accepts unencoded visible 7-bit ASCII characters in addition to the RFC.
   * If the given string is not a valid URI the method throws an `IllegalUriException`.
   */
  def apply(input: ParserInput, charset: Charset, mode: Uri.ParsingMode): Uri =
    new UriParser(input, charset, mode).parseUriReference()

  /**
   * Creates a new Uri instance from the given components.
   * All components are verified and normalized except the authority which is kept as provided.
   * If the given combination of components does not constitute a valid URI as defined by
   * http://tools.ietf.org/html/rfc3986 the method throws an `IllegalUriException`.
   */
  def apply(scheme: String = "", authority: Authority = Authority.Empty, path: Path = Path.Empty,
            queryString: Option[String] = None, fragment: Option[String] = None): Uri = {
    val p = verifyPath(path, scheme, authority.host)
    create(
      scheme = normalizeScheme(scheme),
      authority = authority,
      path = if (scheme.isEmpty) p else collapseDotSegments(p),
      queryString = queryString,
      fragment = fragment)
  }

  /**
   * Creates a new Uri instance from the given components.
   * All components are verified and normalized.
   * If the given combination of components does not constitute a valid URI as defined by
   * http://tools.ietf.org/html/rfc3986 the method throws an `IllegalUriException`.
   */
  def from(scheme: String = "", userinfo: String = "", host: String = "", port: Int = 0, path: String = "",
           queryString: Option[String] = None, fragment: Option[String] = None,
           mode: Uri.ParsingMode = Uri.ParsingMode.Relaxed): Uri =
    apply(scheme, Authority(Host(host, UTF8, mode), normalizePort(port, scheme), userinfo), Path(path), queryString, fragment)

  /**
   * Parses a string into a normalized absolute URI as defined by http://tools.ietf.org/html/rfc3986#section-4.3.
   * Percent-encoded octets are decoded using the given charset (where specified by the RFC).
   * If strict is `false`, accepts unencoded visible 7-bit ASCII characters in addition to the RFC.
   * If the given string is not a valid URI the method throws an `IllegalUriException`.
   */
  def parseAbsolute(input: ParserInput, charset: Charset = UTF8, mode: Uri.ParsingMode = Uri.ParsingMode.Relaxed): Uri =
    new UriParser(input, charset, mode).parseAbsoluteUri()

  /**
   * Parses a string into a normalized URI reference that is immediately resolved against the given base URI as
   * defined by http://tools.ietf.org/html/rfc3986#section-5.2.
   * Note that the given base Uri must be absolute (i.e. define a scheme).
   * Percent-encoded octets are decoded using the given charset (where specified by the RFC).
   * If strict is `false`, accepts unencoded visible 7-bit ASCII characters in addition to the RFC.
   * If the given string is not a valid URI the method throws an `IllegalUriException`.
   */
  def parseAndResolve(string: ParserInput, base: Uri, charset: Charset = UTF8,
                      mode: Uri.ParsingMode = Uri.ParsingMode.Relaxed): Uri =
    new UriParser(string, charset, mode).parseAndResolveUriReference(base)

  /**
   * Parses the given string into an HTTP request target URI as defined by
   * http://tools.ietf.org/html/rfc7230#section-5.3.
   * If strict is `false`, accepts unencoded visible 7-bit ASCII characters in addition to the RFC.
   * If the given string is not a valid URI the method throws an `IllegalUriException`.
   */
  def parseHttpRequestTarget(requestTarget: ParserInput, charset: Charset = UTF8,
                             mode: Uri.ParsingMode = Uri.ParsingMode.Relaxed): Uri =
    new UriParser(requestTarget, charset, mode).parseHttpRequestTarget()

  /**
   * Normalizes the given URI string by performing the following normalizations:
   *  - the `scheme` and `host` components are converted to lowercase
   *  - a potentially existing `port` component is removed if it matches one of the defined default ports for the scheme
   *  - percent-encoded octets are decoded if allowed, otherwise they are converted to uppercase hex notation
   *  - `.` and `..` path segments are resolved as far as possible
   *
   * If strict is `false`, accepts unencoded visible 7-bit ASCII characters in addition to the RFC.
   * If the given string is not a valid URI the method throws an `IllegalUriException`.
   */
  def normalize(uri: ParserInput, charset: Charset = UTF8, mode: Uri.ParsingMode = Uri.ParsingMode.Relaxed): String = {
    val parsed = apply(uri, charset, mode)
    val normalized = parsed.copy(authority = parsed.authority.normalizedFor(parsed.scheme))
    UriRendering.renderUri(new StringRendering, normalized, charset).get
  }

  /**
   * Converts a set of URI components to an "effective HTTP request URI" as defined by
   * http://tools.ietf.org/html/rfc7230#section-5.5.
   */
  def effectiveHttpRequestUri(scheme: String, host: Host, port: Int, path: Path, query: Option[String], fragment: Option[String],
                              securedConnection: Boolean, hostHeaderHost: Host, hostHeaderPort: Int,
                              defaultAuthority: Authority = Authority.Empty): Uri = {
    var _scheme = scheme
    var _host = host
    var _port = port
    if (_scheme.isEmpty) {
      _scheme = httpScheme(securedConnection)
      if (_host.isEmpty) {
        if (hostHeaderHost.isEmpty) {
          _host = defaultAuthority.host
          _port = defaultAuthority.port
        } else {
          _host = hostHeaderHost
          _port = hostHeaderPort
        }
      }
    }
    create(_scheme, "", _host, _port, collapseDotSegments(path), query, fragment)
  }

  def httpScheme(securedConnection: Boolean = false) = if (securedConnection) "https" else "http"

  /**
   * @param port A port number that may be `0` to signal the default port of for scheme.
   *             In general what you want is not the value of this field but [[Uri.effectivePort]].
   */
  final case class Authority(host: Host, port: Int = 0, userinfo: String = "") {
    def isEmpty = equals(Authority.Empty)
    def nonEmpty = !isEmpty
    def normalizedForHttp(encrypted: Boolean = false) =
      normalizedFor(httpScheme(encrypted))
    def normalizedFor(scheme: String): Authority = {
      val normalizedPort = normalizePort(port, scheme)
      if (normalizedPort == port) this else copy(port = normalizedPort)
    }
    override def toString = UriRendering.AuthorityRenderer.render(new StringRendering, this).get
  }
  object Authority {
    val Empty = Authority(Host.Empty)
  }

  sealed abstract class Host extends jm.Host {
    def address: String
    def isEmpty: Boolean
    def toOption: Option[NonEmptyHost]
    def inetAddresses: immutable.Seq[InetAddress]

    def equalsIgnoreCase(other: Host): Boolean
    override def toString = UriRendering.HostRenderer.render(new StringRendering, this).get

    // default implementations
    def isNamedHost: Boolean = false
    def isIPv6: Boolean = false
    def isIPv4: Boolean = false

    /** Java API */
    def getInetAddresses: Iterable[InetAddress] = {
      import akka.http.impl.util.JavaMapping.Implicits._
      inetAddresses.asJava
    }
  }
  object Host {
    case object Empty extends Host {
      def address: String = ""
      def isEmpty = true
      def toOption = None
      def inetAddresses: immutable.Seq[InetAddress] = Nil

      def equalsIgnoreCase(other: Host): Boolean = other eq this
    }
    def apply(string: String, charset: Charset = UTF8, mode: Uri.ParsingMode = Uri.ParsingMode.Relaxed): Host =
      if (!string.isEmpty) new UriParser(string, UTF8, mode).parseHost() else Empty

    def apply(address: InetAddress): Host = address match {
      case ipv4: Inet4Address ⇒ apply(ipv4)
      case ipv6: Inet6Address ⇒ apply(ipv6)
      case _                  ⇒ throw new IllegalArgumentException(s"Unexpected address type(${address.getClass.getSimpleName}): $address")
    }
    def apply(address: Inet4Address): IPv4Host = IPv4Host(address.getAddress, address.getHostAddress)
    def apply(address: Inet6Address): IPv6Host = IPv6Host(address.getAddress, address.getHostAddress)
  }

  sealed abstract class NonEmptyHost extends Host {
    def isEmpty = false
    def toOption = Some(this)
  }
  final case class IPv4Host private[http] (bytes: immutable.Seq[Byte], address: String) extends NonEmptyHost {
    require(bytes.length == 4, "bytes array must have length 4")
    require(!address.isEmpty, "address must not be empty")
    def equalsIgnoreCase(other: Host): Boolean = other match {
      case IPv4Host(`bytes`, _) ⇒ true
      case _                    ⇒ false
    }

    override def isIPv4: Boolean = true
    def inetAddresses = immutable.Seq(InetAddress.getByAddress(bytes.toArray))
  }
  object IPv4Host {
    def apply(address: String): IPv4Host = apply(address.split('.').map(_.toInt.toByte))
    def apply(byte1: Byte, byte2: Byte, byte3: Byte, byte4: Byte): IPv4Host = apply(Array(byte1, byte2, byte3, byte4))
    def apply(bytes: Array[Byte]): IPv4Host = apply(bytes, bytes.map(_ & 0xFF).mkString("."))

    private[http] def apply(bytes: Array[Byte], address: String): IPv4Host = IPv4Host(immutable.Seq(bytes: _*), address)
  }
  final case class IPv6Host private (bytes: immutable.Seq[Byte], address: String) extends NonEmptyHost {
    require(bytes.length == 16, "bytes array must have length 16")
    require(!address.isEmpty, "address must not be empty")
    def equalsIgnoreCase(other: Host): Boolean = other match {
      case IPv6Host(`bytes`, _) ⇒ true
      case _                    ⇒ false
    }

    override def isIPv6: Boolean = true
    def inetAddresses = immutable.Seq(InetAddress.getByAddress(bytes.toArray))
  }
  object IPv6Host {
    def apply(bytes: Array[Byte]): IPv6Host = Host(InetAddress.getByAddress(bytes).asInstanceOf[Inet6Address])
    def apply(bytes: immutable.Seq[Byte]): IPv6Host = apply(bytes.toArray)

    private[http] def apply(bytes: String, address: String): IPv6Host = {
      import CharUtils.{ hexValue ⇒ hex }
      require(bytes.length == 32, "`bytes` must be a 32 character hex string")
      apply(bytes.toCharArray.grouped(2).map(s ⇒ (hex(s(0)) * 16 + hex(s(1))).toByte).toArray, address)
    }
    private[http] def apply(bytes: Array[Byte], address: String): IPv6Host = apply(immutable.Seq(bytes: _*), address)
  }
  final case class NamedHost(address: String) extends NonEmptyHost {
    def equalsIgnoreCase(other: Host): Boolean = other match {
      case NamedHost(otherAddress) ⇒ address equalsIgnoreCase otherAddress
      case _                       ⇒ false
    }

    override def isNamedHost: Boolean = true
    def inetAddresses = InetAddress.getAllByName(address).toList
  }

  sealed abstract class Path {
    type Head
    def isEmpty: Boolean
    def startsWithSlash: Boolean
    def startsWithSegment: Boolean
    def endsWithSlash: Boolean = {
      import Path.{ Empty ⇒ PEmpty, _ }
      @tailrec def check(path: Path): Boolean = path match {
        case PEmpty              ⇒ false
        case Slash(PEmpty)       ⇒ true
        case Slash(tail)         ⇒ check(tail)
        case Segment(head, tail) ⇒ check(tail)
      }
      check(this)
    }
    def head: Head
    def tail: Path
    def length: Int
    def charCount: Int // count of decoded (!) chars, i.e. the ones contained directly in this high-level model
    def ::(c: Char): Path = { require(c == '/'); Path.Slash(this) }
    def ::(segment: String): Path
    def +(pathString: String): Path = this ++ Path(pathString)
    def ++(suffix: Path): Path
    def reverse: Path = reverseAndPrependTo(Path.Empty)
    def reverseAndPrependTo(prefix: Path): Path
    def /(segment: String): Path = this ++ Path.Slash(segment :: Path.Empty)
    def startsWith(that: Path): Boolean
    def dropChars(count: Int): Path
    override def toString = UriRendering.PathRenderer.render(new StringRendering, this).get
  }
  object Path {
    val SingleSlash = Slash(Empty)
    def / : Path = SingleSlash
    def /(path: Path): Path = Slash(path)
    def /(segment: String): Path = Slash(segment :: Empty)
    def apply(string: String, charset: Charset = UTF8): Path = {
      @tailrec def build(path: Path = Empty, ix: Int = string.length - 1, segmentEnd: Int = 0): Path =
        if (ix >= 0)
          if (string.charAt(ix) == '/')
            if (segmentEnd == 0) build(Slash(path), ix - 1)
            else build(Slash(decode(string.substring(ix + 1, segmentEnd), charset) :: path), ix - 1)
          else if (segmentEnd == 0) build(path, ix - 1, ix + 1)
          else build(path, ix - 1, segmentEnd)
        else if (segmentEnd == 0) path else decode(string.substring(0, segmentEnd), charset) :: path
      build()
    }
    def unapply(path: Path): Option[String] = Some(path.toString)
    def unapply(uri: Uri): Option[String] = unapply(uri.path)
    sealed abstract class SlashOrEmpty extends Path {
      def startsWithSegment = false
    }
    case object Empty extends SlashOrEmpty {
      type Head = Nothing
      def isEmpty = true
      def startsWithSlash = false
      def head: Head = throw new NoSuchElementException("head of empty path")
      def tail: Path = throw new UnsupportedOperationException("tail of empty path")
      def length = 0
      def charCount = 0
      def ::(segment: String) = if (segment.isEmpty) this else Segment(segment, this)
      def ++(suffix: Path) = suffix
      def reverseAndPrependTo(prefix: Path) = prefix
      def startsWith(that: Path): Boolean = that.isEmpty
      def dropChars(count: Int) = this
    }
    final case class Slash(tail: Path) extends SlashOrEmpty {
      type Head = Char
      def head = '/'
      def startsWithSlash = true
      def isEmpty = false
      def length: Int = tail.length + 1
      def charCount: Int = tail.charCount + 1
      def ::(segment: String) = if (segment.isEmpty) this else Segment(segment, this)
      def ++(suffix: Path) = Slash(tail ++ suffix)
      def reverseAndPrependTo(prefix: Path) = tail.reverseAndPrependTo(Slash(prefix))
      def startsWith(that: Path): Boolean = that.isEmpty || that.startsWithSlash && tail.startsWith(that.tail)
      def dropChars(count: Int): Path = if (count < 1) this else tail.dropChars(count - 1)
    }
    final case class Segment(head: String, tail: SlashOrEmpty) extends Path {
      if (head.isEmpty) throw new IllegalArgumentException("Path segment must not be empty")
      type Head = String
      def isEmpty = false
      def startsWithSlash = false
      def startsWithSegment = true
      def length: Int = tail.length + 1
      def charCount: Int = head.length + tail.charCount
      def ::(segment: String) = if (segment.isEmpty) this else Segment(segment + head, tail)
      def ++(suffix: Path) = head :: (tail ++ suffix)
      def reverseAndPrependTo(prefix: Path): Path = tail.reverseAndPrependTo(head :: prefix)
      def startsWith(that: Path): Boolean = that match {
        case Segment(`head`, t) ⇒ tail.startsWith(t)
        case Segment(h, Empty)  ⇒ head.startsWith(h)
        case x                  ⇒ x.isEmpty
      }
      def dropChars(count: Int): Path =
        if (count < 1) this
        else if (count >= head.length) tail.dropChars(count - head.length)
        else head.substring(count) :: tail
    }
    object ~ {
      def unapply(cons: Segment): Option[(String, Path)] = Some((cons.head, cons.tail))
      def unapply(cons: Slash): Option[(Char, Path)] = Some(('/', cons.tail))
    }
  }

  sealed abstract class Query extends LinearSeq[(String, String)] with LinearSeqOptimized[(String, String), Query] {
    def key: String
    def value: String
    def +:(kvp: (String, String)) = Query.Cons(kvp._1, kvp._2, this)
    def get(key: String): Option[String] = {
      @tailrec def g(q: Query): Option[String] = if (q.isEmpty) None else if (q.key == key) Some(q.value) else g(q.tail)
      g(this)
    }
    def getOrElse(key: String, default: ⇒ String): String = {
      @tailrec def g(q: Query): String = if (q.isEmpty) default else if (q.key == key) q.value else g(q.tail)
      g(this)
    }
    def getAll(key: String): List[String] = {
      @tailrec def fetch(q: Query, result: List[String] = Nil): List[String] =
        if (q.isEmpty) result else fetch(q.tail, if (q.key == key) q.value :: result else result)
      fetch(this)
    }
    def toMap: Map[String, String] = {
      @tailrec def append(map: Map[String, String], q: Query): Map[String, String] =
        if (q.isEmpty) map else append(map.updated(q.key, q.value), q.tail)
      append(Map.empty, this)
    }
    def toMultiMap: Map[String, List[String]] = {
      @tailrec def append(map: Map[String, List[String]], q: Query): Map[String, List[String]] =
        if (q.isEmpty) map else append(map.updated(q.key, q.value :: map.getOrElse(q.key, Nil)), q.tail)
      append(Map.empty, this)
    }
    override def newBuilder: mutable.Builder[(String, String), Query] = Query.newBuilder
    override def toString = UriRendering.QueryRenderer.render(new StringRendering, this).get
  }
  object Query {
    /** A special empty String value which will be rendered without a '=' after the key. */
    val EmptyValue: String = new String(Array.empty[Char])

    /**
     * Parses the given String into a Query instance.
     * Note that this method will never return Query.Empty, even for the empty String.
     * Empty strings will be parsed to `("", "") +: Query.Empty`
     * If you want to allow for Query.Empty creation use the apply overload taking an `Option[String]`.
     */
    def apply(string: String): Query = apply(string: ParserInput, UTF8, Uri.ParsingMode.Relaxed)
    def apply(input: ParserInput, charset: Charset = UTF8, mode: Uri.ParsingMode = Uri.ParsingMode.Relaxed): Query =
      new UriParser(input, charset, mode).parseQuery()
    def apply(input: Option[String]): Query = apply(input, UTF8, Uri.ParsingMode.Relaxed)
    def apply(input: Option[String], charset: Charset, mode: Uri.ParsingMode): Query = input match {
      case None         ⇒ Query.Empty
      case Some(string) ⇒ apply(string, charset, mode)
    }
    def apply(params: (String, String)*): Query =
      params.foldRight(Query.Empty: Query) { case ((key, value), acc) ⇒ Cons(key, value, acc) }
    def apply(params: Map[String, String]): Query = apply(params.toSeq: _*)

    def newBuilder: mutable.Builder[(String, String), Query] = new mutable.Builder[(String, String), Query] {
      val b = mutable.ArrayBuffer.newBuilder[(String, String)]
      def +=(elem: (String, String)): this.type = { b += elem; this }
      def clear() = b.clear()
      def result() = apply(b.result(): _*)
    }

    case object Empty extends Query {
      def key = throw new NoSuchElementException("key of empty path")
      def value = throw new NoSuchElementException("value of empty path")
      override def isEmpty = true
      override def head = throw new NoSuchElementException("head of empty list")
      override def tail = throw new UnsupportedOperationException("tail of empty query")
    }
    final case class Cons(key: String, value: String, override val tail: Query) extends Query {
      override def isEmpty = false
      override def head = (key, value)
    }
  }

  private val defaultPorts: Map[String, Int] =
    Map("ftp" → 21, "ssh" → 22, "telnet" → 23, "smtp" → 25, "domain" → 53, "tftp" → 69, "http" → 80, "ws" → 80,
      "pop3" → 110, "nntp" → 119, "imap" → 143, "snmp" → 161, "ldap" → 389, "https" → 443, "wss" → 443, "imaps" → 993,
      "nfs" → 2049).withDefaultValue(-1)

  sealed trait ParsingMode extends akka.http.javadsl.model.Uri.ParsingMode
  object ParsingMode {
    case object Strict extends ParsingMode
    case object Relaxed extends ParsingMode

    def apply(string: String): ParsingMode =
      string match {
        case "strict"  ⇒ Strict
        case "relaxed" ⇒ Relaxed
        case x         ⇒ throw new IllegalArgumentException(x + " is not a legal UriParsingMode")
      }
  }

  // http://tools.ietf.org/html/rfc3986#section-5.2.2
  private[http] def resolve(scheme: String, userinfo: String, host: Host, port: Int, path: Path, query: Option[String],
                            fragment: Option[String], base: Uri): Uri = {
    require(base.isAbsolute, "Resolution base Uri must be absolute")
    if (scheme.isEmpty)
      if (host.isEmpty)
        if (path.isEmpty) {
          val q = if (query.isEmpty) base.rawQueryString else query
          create(base.scheme, base.authority, base.path, q, fragment)
        } else {
          // http://tools.ietf.org/html/rfc3986#section-5.2.3
          def mergePaths(base: Uri, path: Path): Path =
            if (!base.authority.isEmpty && base.path.isEmpty) Path.Slash(path)
            else {
              import Path._
              def replaceLastSegment(p: Path, replacement: Path): Path = p match {
                case Path.Empty | Segment(_, Path.Empty) ⇒ replacement
                case Segment(string, tail)               ⇒ string :: replaceLastSegment(tail, replacement)
                case Slash(tail)                         ⇒ Slash(replaceLastSegment(tail, replacement))
              }
              replaceLastSegment(base.path, path)
            }
          val p = if (path.startsWithSlash) path else mergePaths(base, path)
          create(base.scheme, base.authority, collapseDotSegments(p), query, fragment)
        }
      else create(base.scheme, userinfo, host, port, collapseDotSegments(path), query, fragment)
    else create(scheme, userinfo, host, port, collapseDotSegments(path), query, fragment)
  }

  private[http] def decode(string: String, charset: Charset): String = {
    val ix = string.indexOf('%')
    if (ix >= 0) decode(string, charset, ix)() else string
  }

  @tailrec
  private[http] def decode(string: String, charset: Charset, ix: Int)(sb: JStringBuilder = new JStringBuilder(string.length).append(string, 0, ix)): String =
    if (ix < string.length) string.charAt(ix) match {
      case '%' ⇒
        def intValueOfHexWord(i: Int) = {
          def intValueOfHexChar(j: Int) = {
            val c = string.charAt(j)
            if (HEXDIG(c)) CharUtils.hexValue(c)
            else throw new IllegalArgumentException("Illegal percent-encoding at pos " + j)
          }
          intValueOfHexChar(i) * 16 + intValueOfHexChar(i + 1)
        }

        var lastPercentSignIndexPlus3 = ix + 3
        while (lastPercentSignIndexPlus3 < string.length && string.charAt(lastPercentSignIndexPlus3) == '%')
          lastPercentSignIndexPlus3 += 3
        val bytesCount = (lastPercentSignIndexPlus3 - ix) / 3
        val bytes = new Array[Byte](bytesCount)

        @tailrec def decodeBytes(i: Int = 0, oredBytes: Int = 0): Int =
          if (i < bytesCount) {
            val byte = intValueOfHexWord(ix + 3 * i + 1)
            bytes(i) = byte.toByte
            decodeBytes(i + 1, oredBytes | byte)
          } else oredBytes

        // if we have only ASCII chars and the charset is ASCII compatible we don't need to involve it in decoding
        if (((decodeBytes() >> 7) == 0) && UriRendering.isAsciiCompatible(charset)) {
          @tailrec def appendBytes(i: Int = 0): Unit =
            if (i < bytesCount) { sb.append(bytes(i).toChar); appendBytes(i + 1) }
          appendBytes()
        } else sb.append(new String(bytes, charset))
        decode(string, charset, lastPercentSignIndexPlus3)(sb)

      case x ⇒ decode(string, charset, ix + 1)(sb.append(x))
    }
    else sb.toString

  private[http] def normalizeScheme(scheme: String): String = {
    @tailrec def verify(ix: Int = scheme.length - 1, allowed: CharPredicate = ALPHA, allLower: Boolean = true): Int =
      if (ix >= 0) {
        val c = scheme.charAt(ix)
        if (allowed(c)) verify(ix - 1, `scheme-char`, allLower && !UPPER_ALPHA(c)) else ix
      } else if (allLower) -1 else -2
    verify() match {
      case -2 ⇒ scheme.toLowerCase
      case -1 ⇒ scheme
      case ix ⇒ fail(s"Invalid URI scheme, unexpected character at pos $ix ('${scheme charAt ix}')")
    }
  }

  private[http] def normalizePort(port: Int, scheme: String): Int =
    if ((port >> 16) == 0)
      if (port != 0 && defaultPorts(scheme) == port) 0 else port
    else fail("Invalid port " + port)

  private[http] def verifyPath(path: Path, scheme: String, host: Host): Path = {
    if (host.isEmpty) {
      if (path.startsWithSlash && path.tail.startsWithSlash)
        fail("""The path of an URI without authority must not begin with "//"""")
    } else if (path.startsWithSegment)
      fail("The path of an URI containing an authority must either be empty or start with a '/' (slash) character")
    path
  }

  private[http] def collapseDotSegments(path: Path): Path = {
    @tailrec def hasDotOrDotDotSegment(p: Path): Boolean = p match {
      case Path.Empty ⇒ false
      case Path.Segment(".", _) | Path.Segment("..", _) ⇒ true
      case _ ⇒ hasDotOrDotDotSegment(p.tail)
    }
    // http://tools.ietf.org/html/rfc3986#section-5.2.4
    @tailrec def process(input: Path, output: Path = Path.Empty): Path = {
      import Path._
      input match {
        case Path.Empty                       ⇒ output.reverse
        case Segment("." | "..", Slash(tail)) ⇒ process(tail, output)
        case Slash(Segment(".", tail))        ⇒ process(if (tail.isEmpty) Path./ else tail, output)
        case Slash(Segment("..", tail)) ⇒ process(
          input = if (tail.isEmpty) Path./ else tail,
          output =
          if (output.startsWithSegment)
            if (output.tail.startsWithSlash) output.tail.tail else tail
          else output)
        case Segment("." | "..", tail) ⇒ process(tail, output)
        case Slash(tail)               ⇒ process(tail, Slash(output))
        case Segment(string, tail)     ⇒ process(tail, string :: output)
      }
    }
    if (hasDotOrDotDotSegment(path)) process(path) else path
  }

  private[http] def fail(summary: String, detail: String = "") = throw IllegalUriException(summary, detail)

  private[http] def create(scheme: String, userinfo: String, host: Host, port: Int, path: Path, queryString: Option[String],
                           fragment: Option[String]): Uri =
    create(scheme, Authority(host, port, userinfo), path, queryString, fragment)

  private[http] def create(scheme: String, authority: Authority, path: Path, queryString: Option[String],
                           fragment: Option[String]): Uri =
    if (path.isEmpty && scheme.isEmpty && authority.isEmpty && queryString.isEmpty && fragment.isEmpty) Empty
    else new Uri(scheme, authority, path, queryString, fragment) { def isEmpty = false }
}

object UriRendering {
  implicit object HostRenderer extends Renderer[Host] {
    def render[R <: Rendering](r: R, value: Host): r.type = value match {
      case Host.Empty           ⇒ r
      case IPv4Host(_, address) ⇒ r ~~ address
      case IPv6Host(_, address) ⇒ r ~~ '[' ~~ address ~~ ']'
      case NamedHost(address)   ⇒ encode(r, address, UTF8, `reg-name-char`)
    }
  }
  implicit object AuthorityRenderer extends Renderer[Authority] {
    def render[R <: Rendering](r: R, value: Authority): r.type = renderAuthority(r, value, "", UTF8)
  }
  implicit object PathRenderer extends Renderer[Path] {
    def render[R <: Rendering](r: R, value: Path): r.type = renderPath(r, value, UTF8)
  }
  implicit object QueryRenderer extends Renderer[Query] {
    def render[R <: Rendering](r: R, value: Query): r.type = renderQuery(r, value, UTF8)
  }
  implicit object UriRenderer extends Renderer[Uri] {
    def render[R <: Rendering](r: R, value: Uri): r.type = renderUri(r, value, UTF8)
  }

  /**
   * Renders this Uri into the given Renderer as defined by http://tools.ietf.org/html/rfc3986.
   * All Uri components are encoded and joined as required by the spec. The given charset is used to
   * produce percent-encoded representations of potentially existing non-ASCII characters in the
   * different components.
   */
  def renderUri[R <: Rendering](r: R, value: Uri, charset: Charset): r.type = {
    renderUriWithoutFragment(r, value, charset)
    if (value.fragment.isDefined) encode(r ~~ '#', value.fragment.get, charset, `query-fragment-char`)
    r
  }

  /**
   * Renders this Uri (without the fragment component) into the given Renderer as defined by
   * http://tools.ietf.org/html/rfc3986.
   * All Uri components are encoded and joined as required by the spec. The given charset is used to
   * produce percent-encoded representations of potentially existing non-ASCII characters in the
   * different components.
   */
  def renderUriWithoutFragment[R <: Rendering](r: R, value: Uri, charset: Charset): r.type = {
    import value._
    if (isAbsolute) r ~~ scheme ~~ ':'
    renderAuthority(r, authority, path, scheme, charset)
    renderPath(r, path, charset, encodeFirstSegmentColons = isRelative)
    rawQueryString.foreach(r ~~ '?' ~~ _)
    r
  }

  def renderAuthority[R <: Rendering](r: R, authority: Authority, scheme: String, charset: Charset): r.type =
    renderAuthority(r, authority, Path.Empty, scheme, charset)

  def renderAuthority[R <: Rendering](r: R, authority: Authority, path: Path, scheme: String, charset: Charset): r.type =
    if (authority.nonEmpty) {
      import authority._
      r ~~ '/' ~~ '/'
      if (!userinfo.isEmpty) encode(r, userinfo, charset, `userinfo-char`) ~~ '@'
      r ~~ host
      if (port != 0) r ~~ ':' ~~ port else r
    } else scheme match {
      case "" | "mailto" ⇒ r
      case _             ⇒ if (path.isEmpty || path.startsWithSlash) r ~~ '/' ~~ '/' else r
    }

  def renderPath[R <: Rendering](r: R, path: Path, charset: Charset, encodeFirstSegmentColons: Boolean = false): r.type =
    path match {
      case Path.Empty       ⇒ r
      case Path.Slash(tail) ⇒ renderPath(r ~~ '/', tail, charset)
      case Path.Segment(head, tail) ⇒
        val keep = if (encodeFirstSegmentColons) `pchar-base-nc` else `pchar-base`
        renderPath(encode(r, head, charset, keep), tail, charset)
    }

  def renderQuery[R <: Rendering](r: R, query: Query, charset: Charset,
                                  keep: CharPredicate = `strict-query-char-np`): r.type = {
    def enc(s: String): Unit = encode(r, s, charset, keep, replaceSpaces = true)
    @tailrec def append(q: Query): r.type =
      q match {
        case Query.Empty ⇒ r
        case Query.Cons(key, value, tail) ⇒
          if (q ne query) r ~~ '&'
          enc(key)
          if (value ne Query.EmptyValue) r ~~ '='
          enc(value)
          append(tail)
      }
    append(query)
  }

  private[http] def encode(r: Rendering, string: String, charset: Charset, keep: CharPredicate,
                           replaceSpaces: Boolean = false): r.type = {
    val asciiCompatible = isAsciiCompatible(charset)
    @tailrec def rec(ix: Int): r.type = {
      def appendEncoded(byte: Byte): Unit = r ~~ '%' ~~ CharUtils.upperHexDigit(byte >>> 4) ~~ CharUtils.upperHexDigit(byte)
      if (ix < string.length) {
        val charSize = string.charAt(ix) match {
          case c if keep(c)                     ⇒ { r ~~ c; 1 }
          case ' ' if replaceSpaces             ⇒ { r ~~ '+'; 1 }
          case c if c <= 127 && asciiCompatible ⇒ { appendEncoded(c.toByte); 1 }
          case c ⇒
            def append(s: String) = s.getBytes(charset).foreach(appendEncoded)
            if (Character.isHighSurrogate(c)) { append(new String(Array(string codePointAt ix), 0, 1)); 2 }
            else { append(c.toString); 1 }
        }
        rec(ix + charSize)
      } else r
    }
    rec(0)
  }

  private[http] def isAsciiCompatible(cs: Charset) = cs == UTF8 || cs == ISO88591 || cs == ASCII
}

/**
 * INTERNAL API.
 */
abstract class UriJavaAccessor
/**
 * INTERNAL API.
 */
object UriJavaAccessor {
  import collection.JavaConverters._

  def hostApply(string: String): Host = Uri.Host(string)
  def hostApply(string: String, mode: Uri.ParsingMode): Host = Uri.Host(string, mode = mode)
  def hostApply(string: String, charset: Charset): Host = Uri.Host(string, charset = charset)
  def emptyHost: Uri.Host = Uri.Host.Empty

  def queryApply(params: Array[akka.japi.Pair[String, String]]): Uri.Query = Uri.Query(params.map(_.toScala): _*)
  def queryApply(params: java.util.Map[String, String]): Uri.Query = Uri.Query(params.asScala.toSeq: _*)
  def queryApply(string: String, mode: Uri.ParsingMode): Uri.Query = Uri.Query(string, mode = mode)
  def queryApply(string: String, charset: Charset): Uri.Query = Uri.Query(string, charset = charset)
  def emptyQuery: Uri.Query = Uri.Query.Empty

  def pmStrict: Uri.ParsingMode = Uri.ParsingMode.Strict
  def pmRelaxed: Uri.ParsingMode = Uri.ParsingMode.Relaxed
}
