/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import java.security.SecureRandom
import java.util
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLContextSpi
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSessionContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager

import akka.http.scaladsl.HttpsConnectionContext
import org.eclipse.jetty.alpn.ALPN

/**
 * Wraps an SSLContext so that all engines it creates will announce HTTP/2 if possible.
 */
class WrappedSslContextSPI(underlying: SSLContext) extends SSLContextSpi {
  def engineGetSocketFactory(): SSLSocketFactory =
    underlying.getSocketFactory()

  def engineInit(keyManagers: Array[KeyManager], trustManagers: Array[TrustManager], secureRandom: SecureRandom): Unit =
    underlying.init(keyManagers, trustManagers, secureRandom)

  def engineCreateSSLEngine(): SSLEngine = {
    val engine = underlying.createSSLEngine()
    ALPN.put(engine, new ALPN.ServerProvider {
      def select(protocols: util.List[String]): String =
        try
          if (protocols.contains("h2")) {
            System.out.println("HTTP/2 is supported!")
            "h2"
          } else
            // FIXME: actually negotiate and fall back to HTTP/1.1
            throw new SSLException("No supported protocol found. Only 'h2' supported right now.")
        finally ALPN.remove(engine)

      def unsupported(): Unit = {
        println("ALPN not supported by client!") // FIXME: remove
        ALPN.remove(engine)
      }
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

object WrappedSslContextSPI {
  val field = {
    val field = classOf[SSLContext].getDeclaredField("contextSpi")
    field.setAccessible(true)
    field
  }

  def wrapContext(context: HttpsConnectionContext): HttpsConnectionContext = {
    val newContext = SSLContext.getInstance("TLS")
    field.set(newContext, new WrappedSslContextSPI(context.sslContext))

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

