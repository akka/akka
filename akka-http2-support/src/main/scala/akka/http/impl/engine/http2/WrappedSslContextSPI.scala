/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import java.security.SecureRandom
import java.{ util ⇒ ju }
import javax.net.ssl._

import akka.http.scaladsl.HttpsConnectionContext
import org.eclipse.jetty.alpn.ALPN
import org.eclipse.jetty.alpn.ALPN.ServerProvider

/**
 * INTERNAL API
 *
 * Wraps an SSLContext so that all engines it creates will announce HTTP/2 if possible.
 */
private[akka] class WrappedSslContextSPI(underlying: SSLContext, setChosenProtocol: String ⇒ Unit) extends SSLContextSpi {
  def engineGetSocketFactory(): SSLSocketFactory =
    underlying.getSocketFactory()

  def engineInit(keyManagers: Array[KeyManager], trustManagers: Array[TrustManager], secureRandom: SecureRandom): Unit =
    underlying.init(keyManagers, trustManagers, secureRandom)

  def engineCreateSSLEngine(): SSLEngine = {

    val engine = underlying.createSSLEngine()

    ALPN.put(engine, new ServerProvider {
      override def select(protocols: ju.List[String]): String =
        choose {
          if (protocols.contains("h2")) "h2"
          else "h1"
        }

      override def unsupported(): Unit =
        choose("h1")

      def choose(protocol: String): String = try {
        setChosenProtocol(protocol)
        protocol
      } finally ALPN.remove(engine)
    })
    engine
  }

  def engineCreateSSLEngine(s: String, i: Int): SSLEngine =
    underlying.createSSLEngine(s, i)

  def engineGetClientSessionContext(): SSLSessionContext =
    underlying.getClientSessionContext()

  def engineGetServerSessionContext(): SSLSessionContext =
    underlying.getServerSessionContext()

  def engineGetServerSocketFactory(): SSLServerSocketFactory =
    underlying.getServerSocketFactory()
}

private[akka] object WrappedSslContextSPI {
  val field = {
    val field = classOf[SSLContext].getDeclaredField("contextSpi")
    field.setAccessible(true)
    field
  }

  def wrapContext(context: HttpsConnectionContext, setChosenProtocol: String ⇒ Unit): HttpsConnectionContext = {
    val newContext = SSLContext.getInstance("TLS")
    field.set(newContext, new WrappedSslContextSPI(context.sslContext, setChosenProtocol))

    new HttpsConnectionContext(
      newContext,
      context.sslConfig,
      context.enabledCipherSuites,
      context.enabledProtocols,
      context.clientAuth,
      context.sslParameters
    )
  }
}

