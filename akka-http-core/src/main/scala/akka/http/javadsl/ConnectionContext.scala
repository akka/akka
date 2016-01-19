/**
 * Copyright (C) 2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.http.javadsl

import java.util.{ Collection ⇒ JCollection, Optional }
import java.{ util ⇒ ju }
import javax.net.ssl.{ SSLContext, SSLParameters }

import akka.http.scaladsl
import com.typesafe.sslconfig.ssl.ClientAuth

import scala.compat.java8.OptionConverters

object ConnectionContext {
  /** Used to serve HTTPS traffic. */
  def https(sslContext: SSLContext): HttpsConnectionContext =
    scaladsl.ConnectionContext.https(sslContext)

  /** Used to serve HTTPS traffic. */
  def https(sslContext: SSLContext, enabledCipherSuites: Optional[JCollection[String]],
            enabledProtocols: Optional[JCollection[String]], clientAuth: Optional[ClientAuth], sslParameters: Optional[SSLParameters]) =
    scaladsl.ConnectionContext.https(sslContext, sslParameters = OptionConverters.toScala(sslParameters))

  /** Used to serve HTTP traffic. */
  def noEncryption(): HttpConnectionContext =
    scaladsl.ConnectionContext.noEncryption()
}

trait ConnectionContext {
  def isSecure: Boolean
  /** Java API */
  def getDefaultPort: Int
}

trait HttpConnectionContext extends ConnectionContext {
  override final def isSecure = false
  override final def getDefaultPort = 80
}

trait HttpsConnectionContext extends ConnectionContext {
  override final def isSecure = true
  override final def getDefaultPort = 443

  /** Java API */
  def getEnabledCipherSuites: Optional[JCollection[String]]
  /** Java API */
  def getEnabledProtocols: Optional[JCollection[String]]
  /** Java API */
  def getClientAuth: Optional[ClientAuth]

  /** Java API */
  def getSslContext: SSLContext
  /** Java API */
  def getSslParameters: Optional[SSLParameters]
}

