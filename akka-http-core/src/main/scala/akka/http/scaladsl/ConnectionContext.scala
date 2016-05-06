/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.stream.TLSClientAuth
import akka.stream.TLSProtocol._

import scala.collection.JavaConverters._
import java.util.{ Optional, Collection ⇒ JCollection }
import javax.net.ssl._

import scala.collection.immutable
import scala.compat.java8.OptionConverters._

trait ConnectionContext extends akka.http.javadsl.ConnectionContext {
  final def defaultPort = getDefaultPort
}

object ConnectionContext {
  //#https-context-creation
  // ConnectionContext
  def https(sslContext: SSLContext,
            enabledCipherSuites: Option[immutable.Seq[String]] = None,
            enabledProtocols: Option[immutable.Seq[String]] = None,
            clientAuth: Option[TLSClientAuth] = None,
            sslParameters: Option[SSLParameters] = None) = {
    new HttpsConnectionContext(sslContext, enabledCipherSuites, enabledProtocols, clientAuth, sslParameters)
  }
  //#https-context-creation

  def noEncryption() = HttpConnectionContext
}

final class HttpsConnectionContext(
  val sslContext: SSLContext,
  val enabledCipherSuites: Option[immutable.Seq[String]] = None,
  val enabledProtocols: Option[immutable.Seq[String]] = None,
  val clientAuth: Option[TLSClientAuth] = None,
  val sslParameters: Option[SSLParameters] = None)
  extends akka.http.javadsl.HttpsConnectionContext with ConnectionContext {

  def firstSession = NegotiateNewSession(enabledCipherSuites, enabledProtocols, clientAuth, sslParameters)

  override def getSslContext = sslContext
  override def getEnabledCipherSuites: Optional[JCollection[String]] = enabledCipherSuites.map(_.asJavaCollection).asJava
  override def getEnabledProtocols: Optional[JCollection[String]] = enabledProtocols.map(_.asJavaCollection).asJava
  override def getClientAuth: Optional[TLSClientAuth] = clientAuth.asJava
  override def getSslParameters: Optional[SSLParameters] = sslParameters.asJava
}

sealed class HttpConnectionContext extends akka.http.javadsl.HttpConnectionContext with ConnectionContext
final object HttpConnectionContext extends HttpConnectionContext {
  /** Java API */
  def getInstance() = this
}
