/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl

import java.util.{ Collection ⇒ JCollection, Optional }
import javax.net.ssl.{ SSLContext, SSLParameters }
import akka.http.scaladsl
import akka.stream.TLSClientAuth

import scala.compat.java8.OptionConverters

object ConnectionContext {
  //#https-context-creation
  // ConnectionContext
  /** Used to serve HTTPS traffic. */
  def https(sslContext: SSLContext): HttpsConnectionContext =
    scaladsl.ConnectionContext.https(sslContext)

  /** Used to serve HTTPS traffic. */
  def https(sslContext: SSLContext,
            enabledCipherSuites: Optional[JCollection[String]],
            enabledProtocols: Optional[JCollection[String]],
            clientAuth: Optional[TLSClientAuth],
            sslParameters: Optional[SSLParameters]) =
    scaladsl.ConnectionContext.https(sslContext, sslParameters = OptionConverters.toScala(sslParameters))
  //#https-context-creation

  /** Used to serve HTTP traffic. */
  def noEncryption(): HttpConnectionContext =
    scaladsl.ConnectionContext.noEncryption()
}

abstract class ConnectionContext {
  def isSecure: Boolean
  /** Java API */
  def getDefaultPort: Int
}

abstract class HttpConnectionContext extends akka.http.javadsl.ConnectionContext {
  override final def isSecure = false
  override final def getDefaultPort = 80
}

abstract class HttpsConnectionContext extends akka.http.javadsl.ConnectionContext {
  override final def isSecure = true
  override final def getDefaultPort = 443

  /** Java API */
  def getEnabledCipherSuites: Optional[JCollection[String]]
  /** Java API */
  def getEnabledProtocols: Optional[JCollection[String]]
  /** Java API */
  def getClientAuth: Optional[TLSClientAuth]

  /** Java API */
  def getSslContext: SSLContext
  /** Java API */
  def getSslParameters: Optional[SSLParameters]
}
