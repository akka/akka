/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl

import java.util.{ Collection ⇒ JCollection, Optional }
import javax.net.ssl.{ SSLContext, SSLParameters }
import akka.http.scaladsl
import akka.japi.Util
import akka.stream.TLSClientAuth
import akka.http.impl.util.JavaMapping.Implicits._
import com.typesafe.sslconfig.akka.AkkaSSLConfig

import scala.compat.java8.OptionConverters
import scala.collection.JavaConverters._

object ConnectionContext {
  //#https-context-creation
  // ConnectionContext
  /** Used to serve HTTPS traffic. */
  def https(sslContext: SSLContext): HttpsConnectionContext =
    scaladsl.ConnectionContext.https(sslContext)

  /** Used to serve HTTPS traffic. */
  def https(
    sslContext:          SSLContext,
    sslConfig:           Optional[AkkaSSLConfig],
    enabledCipherSuites: Optional[JCollection[String]],
    enabledProtocols:    Optional[JCollection[String]],
    clientAuth:          Optional[TLSClientAuth],
    sslParameters:       Optional[SSLParameters]) =
    scaladsl.ConnectionContext.https(
      sslContext,
      OptionConverters.toScala(sslConfig),
      OptionConverters.toScala(enabledCipherSuites).map(Util.immutableSeq(_)),
      OptionConverters.toScala(enabledProtocols).map(Util.immutableSeq(_)),
      OptionConverters.toScala(clientAuth),
      OptionConverters.toScala(sslParameters))
  //#https-context-creation

  /** Used to serve HTTPS traffic. */
  // for binary-compatibility, since 2.4.7
  def https(
    sslContext:          SSLContext,
    enabledCipherSuites: Optional[JCollection[String]],
    enabledProtocols:    Optional[JCollection[String]],
    clientAuth:          Optional[TLSClientAuth],
    sslParameters:       Optional[SSLParameters]) =
    scaladsl.ConnectionContext.https(
      sslContext,
      OptionConverters.toScala(enabledCipherSuites).map(Util.immutableSeq(_)),
      OptionConverters.toScala(enabledProtocols).map(Util.immutableSeq(_)),
      OptionConverters.toScala(clientAuth),
      OptionConverters.toScala(sslParameters))

  /** Used to serve HTTP traffic. */
  def noEncryption(): HttpConnectionContext =
    scaladsl.ConnectionContext.noEncryption()
}

abstract class ConnectionContext {
  def isSecure: Boolean
  /** Java API */
  def getDefaultPort: Int
  def sslConfig: Option[AkkaSSLConfig]
}

abstract class HttpConnectionContext extends akka.http.javadsl.ConnectionContext {
  override final def isSecure = false
  override final def getDefaultPort = 80
  override def sslConfig: Option[AkkaSSLConfig] = None
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
