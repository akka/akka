/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp.ssl

import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorSystem
import akka.event.Logging
import akka.event.MarkerLoggingAdapter
import akka.remote.artery.tcp.SSLEngineProvider
import akka.remote.artery.tcp.SecureRandomFactory
import akka.remote.artery.tcp.SslTransportException
import akka.remote.artery.tcp.ssl.TlsMagicSSLEngineProvider.CachedContext
import akka.remote.artery.tcp.ssl.TlsMagicSSLEngineProvider.ConfiguredContext
import akka.stream.TLSRole
import com.typesafe.config.Config
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager

import scala.concurrent.duration._

final class TlsMagicSSLEngineProvider(val config: Config, protected val log: MarkerLoggingAdapter)
    extends SSLEngineProvider {

  def this(system: ActorSystem) =
    this(
      system.settings.config.getConfig("akka.remote.artery.ssl.tls-magic-engine"),
      Logging.withMarker(system, classOf[TlsMagicSSLEngineProvider].getName))

  // read config

  private val SSLKeyFile: String = config.getString("key-file")
  private val SSLCertFile: String = config.getString("cert-file")
  private val SSLCACertFile: String = config.getString("ca-cert-file")

  val sslEngineConfig = new SSLEngineConfig(config)
  import sslEngineConfig._

  // build a PRNG (created once, reused on every isntance of SSLContext
  private val rng: SecureRandom = SecureRandomFactory.createSecureRandom(SSLRandomNumberGenerator, log)

  // handle caching
  private val contextRef = new AtomicReference[Option[CachedContext]](None)

  private def getContext() = {
    contextRef.get() match {
      case Some(CachedContext(_, expired)) if expired.isOverdue() =>
        val context = constructContext()
        contextRef.set(Some(CachedContext(context, SSLContextCacheTime.fromNow)))
        context
      case Some(CachedContext(cached, _)) => cached
      case None =>
        val context = constructContext()
        contextRef.set(Some(CachedContext(context, SSLContextCacheTime.fromNow)))
        context
    }
  }

  // Construct the cached instance
  private def constructContext(): ConfiguredContext = {
    try {
      val cert: X509Certificate = PemManagersProvider.loadCertificate(SSLCertFile).asInstanceOf[X509Certificate]
      val cacert: Certificate = PemManagersProvider.loadCertificate(SSLCACertFile)
      val privateKey: PrivateKey = PemManagersProvider.loadPrivateKey(SSLKeyFile)

      val keyManagers: Array[KeyManager] = PemManagersProvider.buildKeyManagers(privateKey, cert, cacert)
      val trustManagers: Array[TrustManager] = PemManagersProvider.buildTrustManagers(cacert)

      val sessionVerifier = new PeerSubjectVerifier(cert)

      val ctx = SSLContext.getInstance(SSLProtocol)
      ctx.init(keyManagers, trustManagers, rng)
      ConfiguredContext(ctx, sessionVerifier)
    } catch {
      case e: FileNotFoundException =>
        throw new SslTransportException(
          "Server SSL connection could not be established because a key or cert could not be loaded",
          e)
      case e: IOException =>
        throw new SslTransportException("Server SSL connection could not be established because: " + e.getMessage, e)
      case e: GeneralSecurityException =>
        throw new SslTransportException(
          "Server SSL connection could not be established because SSL context could not be constructed",
          e)
      case e: IllegalArgumentException =>
        throw new SslTransportException("Server SSL connection could not be established because: " + e.getMessage, e)
    }
  }

  // Implement the SSLEngine create methods from the trait
  override def createServerSSLEngine(hostname: String, port: Int): SSLEngine =
    createSSLEngine(akka.stream.Server, hostname, port)(getContext().context)

  override def createClientSSLEngine(hostname: String, port: Int): SSLEngine =
    createSSLEngine(akka.stream.Client, hostname, port)(getContext().context)

  private def createSSLEngine(role: TLSRole, hostname: String, port: Int)(sslContext: SSLContext) = {

    val engine = sslContext.createSSLEngine(hostname, port)

    engine.setUseClientMode(role == akka.stream.Client)
    engine.setEnabledCipherSuites(SSLEnabledAlgorithms.toArray)
    engine.setEnabledProtocols(Array(SSLProtocol))

    if (role != akka.stream.Client) engine.setNeedClientAuth(true)

    // TODO: Hostname Verification?

    engine
  }

  // Implement the post-handshake verification methods from the trait
  override def verifyClientSession(hostname: String, session: SSLSession): Option[Throwable] =
    getContext().sessionVerifier.verifyClientSession(hostname, session)

  override def verifyServerSession(hostname: String, session: SSLSession): Option[Throwable] =
    getContext().sessionVerifier.verifyServerSession(hostname, session)

}

object TlsMagicSSLEngineProvider {

  private case class CachedContext(cached: ConfiguredContext, expires: Deadline)

  private case class ConfiguredContext(context: SSLContext, sessionVerifier: SessionVerifier)

}
