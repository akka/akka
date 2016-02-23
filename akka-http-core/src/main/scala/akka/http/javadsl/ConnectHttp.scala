/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
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

  /** Extracts host data from given Uri. */
  def toHost(uriHost: Uri): ConnectHttp = {
    val s = uriHost.scheme.toLowerCase(Locale.ROOT)
    if (s == "https") new ConnectHttpsImpl(uriHost.host.address, effectivePort(s, uriHost.port))
    else new ConnectHttpImpl(uriHost.host.address, effectivePort(s, uriHost.port))
  }

  def toHost(host: String): ConnectHttp = {
    if (isHttpOrHttps(host)) toHost(Uri.create(host))
    else toHost(Uri.create(s"http://$host"))
  }

  def toHost(host: String, port: Int): ConnectHttp = {
    require(port > 0, "port must be > 0")
    val start = if (isHttpOrHttps(host)) host else s"http://$host"
    toHost(Uri.create(start).port(port))
  }

  /**
   * Extracts host data from given Uri.
   * Forces an HTTPS connection to the given host, using the default HTTPS context and default port.
   */
  @throws(classOf[IllegalArgumentException])
  def toHostHttps(uriHost: Uri): ConnectWithHttps = {
    val s = uriHost.scheme.toLowerCase(Locale.ROOT)
    require(s == "" || s == "https", "toHostHttps used with non https scheme! Was: " + uriHost)
    val httpsHost = uriHost.scheme("https") // for effective port calculation
    new ConnectHttpsImpl(httpsHost.host.address, effectivePort(uriHost))
  }

  /** Forces an HTTPS connection to the given host, using the default HTTPS context and default port. */
  @throws(classOf[IllegalArgumentException])
  def toHostHttps(host: String): ConnectWithHttps =
    toHostHttps(Uri.create(host))

  /** Forces an HTTPS connection to the given host, using the default HTTPS context and given port. */
  @throws(classOf[IllegalArgumentException])
  def toHostHttps(host: String, port: Int): ConnectWithHttps = {
    require(port > 0, "port must be > 0")
    val start = if (isHttpOrHttps(host)) host else s"https://$host"
    toHostHttps(Uri.create(s"$start").port(port))
  }

  private def isHttpOrHttps(s: String) = s.startsWith("http://") || s.startsWith("https://")

  private def effectivePort(uri: Uri): Int = {
    val s = uri.scheme.toLowerCase(Locale.ROOT)
    effectivePort(s, uri.port)
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
