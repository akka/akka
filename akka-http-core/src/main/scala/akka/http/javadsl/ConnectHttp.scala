/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl

import java.util.Locale
import java.util.Optional

import akka.http.javadsl.model.Uri

abstract class ConnectHttp {
  def host: String
  def port: Int

  def isHttps: Boolean
  def connectionContext: Optional[HttpsConnectionContext]

  final def effectiveHttpsConnectionContext(fallbackContext: HttpsConnectionContext): HttpsConnectionContext =
    connectionContext.orElse(fallbackContext)

  final def effectiveConnectionContext(fallbackContext: ConnectionContext): ConnectionContext =
    if (connectionContext.isPresent) connectionContext.get()
    else fallbackContext

  override def toString = s"ConnectHttp($host,$port,$isHttps,$connectionContext)"
}

object ConnectHttp {

  // TODO may be optimised a bit to avoid parsing the Uri entirely for the known port cases

  /** Extracts HTTP or HTTPS connection data from given Uri. */
  def toHost(uriHost: Uri): ConnectHttp =
    toHost(uriHost, uriHost.port)

  /**
   * Extract HTTP or HTTPS connection data from given host.
   *
   * The host string may contain a URI or a <host>:<port> pair.
   */
  def toHost(host: String): ConnectHttp =
    toHost(createUriWithScheme("http", host))

  /**
   * Extracts HTTP or HTTPS connection data from given host and port.
   *
   * The host string may contain a URI or a <host>:<port> pair. In both cases the
   * port is ignored.
   */
  def toHost(host: String, port: Int): ConnectHttp = {
    require(port > 0, "port must be > 0")
    toHost(createUriWithScheme("http", host), port)
  }

  private def toHost(uriHost: Uri, port: Int): ConnectHttp = {
    val s = uriHost.scheme.toLowerCase(Locale.ROOT)
    if (s == "https") new ConnectHttpsImpl(uriHost.host.address, effectivePort(s, port))
    else new ConnectHttpImpl(uriHost.host.address, effectivePort(s, port))
  }

  /**
   * Extracts HTTPS connection data from given host and port.
   *
   * Uses the default HTTPS context.
   */
  @throws(classOf[IllegalArgumentException])
  def toHostHttps(uriHost: Uri): ConnectWithHttps =
    toHostHttps(uriHost, uriHost.port)

  /**
   * Extracts HTTPS connection data from given host and port.
   *
   * The host string may contain a URI or a <host>:<port> pair.
   *
   * Uses the default HTTPS context.
   */
  @throws(classOf[IllegalArgumentException])
  def toHostHttps(host: String): ConnectWithHttps =
    toHostHttps(createUriWithScheme("https", host))

  /**
   * Extracts HTTPS connection data from given host and port, using the default HTTPS context.
   *
   * The host string may contain a URI or a <host>:<port> pair. In both cases the
   * port is ignored.
   *
   * Uses the default HTTPS context.
   */
  @throws(classOf[IllegalArgumentException])
  def toHostHttps(host: String, port: Int): ConnectWithHttps = {
    require(port > 0, "port must be > 0")
    toHostHttps(createUriWithScheme("https", host), port)
  }

  private def toHostHttps(uriHost: Uri, port: Int): ConnectWithHttps = {
    val s = uriHost.scheme.toLowerCase(Locale.ROOT)
    require(s == "" || s == "https", "toHostHttps used with non https scheme! Was: " + uriHost)
    new ConnectHttpsImpl(uriHost.host.address, effectivePort("https", port))
  }

  private def createUriWithScheme(defaultScheme: String, host: String) = {
    if (host.startsWith("http://") || host.startsWith("https://")) Uri.create(host)
    else Uri.create(s"$defaultScheme://$host")
  }

  private def effectivePort(scheme: String, port: Int): Int = {
    val s = scheme.toLowerCase(Locale.ROOT)
    if (port > 0) port
    else if (s == "https" || s == "wss") 443
    else if (s == "http" || s == "ws") 80
    else throw new IllegalArgumentException("Scheme is not http/https/ws/wss and no port given!")
  }

}

abstract class ConnectWithHttps extends ConnectHttp {
  def withCustomHttpsContext(context: HttpsConnectionContext): ConnectWithHttps
  def withDefaultHttpsContext(): ConnectWithHttps
}

/** INTERNAL API */
final class ConnectHttpImpl(val host: String, val port: Int) extends ConnectHttp {
  def isHttps: Boolean = false

  def connectionContext: Optional[HttpsConnectionContext] = Optional.empty()
}

final class ConnectHttpsImpl(val host: String, val port: Int, val context: Optional[HttpsConnectionContext] = Optional.empty())
  extends ConnectWithHttps {

  override def isHttps: Boolean = true

  override def withCustomHttpsContext(context: HttpsConnectionContext): ConnectWithHttps =
    new ConnectHttpsImpl(host, port, Optional.of(context))

  override def withDefaultHttpsContext(): ConnectWithHttps =
    new ConnectHttpsImpl(host, port, Optional.empty())

  override def connectionContext: Optional[HttpsConnectionContext] = context

}
