/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import java.{ util ⇒ ju }
import javax.net.ssl.{ SSLEngine, SSLParameters }

import akka.stream.TLSClientAuth
import akka.stream.TLSProtocol.NegotiateNewSession
import org.eclipse.jetty.alpn.ALPN
import org.eclipse.jetty.alpn.ALPN.ServerProvider

/**
 * INTERNAL API
 *
 * Will add support to an engine either using jetty alpn or using netty APIs (later).
 */
private[http] object Http2AlpnSupport {
  /**
   * Enables server-side Http/2 ALPN support for the given engine.
   */
  def enableForServer(engine: SSLEngine, setChosenProtocol: String ⇒ Unit): SSLEngine = {
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

  // copy from akka.stream.impl.io.TlsUtils which is inaccessible because of private[stream]
  // FIXME: replace by direct access as should be provided by akka/akka#22116
  def applySessionParameters(engine: SSLEngine, sessionParameters: NegotiateNewSession): Unit = {
    sessionParameters.enabledCipherSuites foreach (cs ⇒ engine.setEnabledCipherSuites(cs.toArray))
    sessionParameters.enabledProtocols foreach (p ⇒ engine.setEnabledProtocols(p.toArray))
    sessionParameters.clientAuth match {
      case Some(TLSClientAuth.None) ⇒ engine.setNeedClientAuth(false)
      case Some(TLSClientAuth.Want) ⇒ engine.setWantClientAuth(true)
      case Some(TLSClientAuth.Need) ⇒ engine.setNeedClientAuth(true)
      case _                        ⇒ // do nothing
    }

    sessionParameters.sslParameters.foreach(engine.setSSLParameters)
  }

  def cloneParameters(old: SSLParameters): SSLParameters = {
    val newParameters = new SSLParameters()
    newParameters.setAlgorithmConstraints(old.getAlgorithmConstraints)
    newParameters.setCipherSuites(old.getCipherSuites)
    newParameters.setEndpointIdentificationAlgorithm(old.getEndpointIdentificationAlgorithm)
    newParameters.setNeedClientAuth(old.getNeedClientAuth)
    newParameters.setProtocols(old.getProtocols)
    newParameters.setServerNames(old.getServerNames)
    newParameters.setSNIMatchers(old.getSNIMatchers)
    newParameters.setUseCipherSuitesOrder(old.getUseCipherSuitesOrder)
    newParameters.setWantClientAuth(old.getWantClientAuth)
    newParameters
  }
}
