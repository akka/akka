/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp.ssl

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorSystem
import akka.annotation.InternalApi
import akka.event.Logging
import akka.event.MarkerLoggingAdapter
import akka.remote.artery.tcp.SSLEngineProvider
import com.typesafe.config.Config
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession

import scala.concurrent.duration._

// TODO: rename this class (rename the settings namespace!)
final class TlsMagicSSLEngineProvider(protected val config: Config, protected val log: MarkerLoggingAdapter)
    extends SSLEngineProvider {

  def this(system: ActorSystem) =
    this(
      // TODO: when renaming the class, review the namespace in config
      system.settings.config.getConfig("akka.remote.artery.ssl.tls-magic-engine"),
      Logging.withMarker(system, classOf[TlsMagicSSLEngineProvider].getName))

  // Cache support
  private val SSLContextCacheTime: FiniteDuration =
    config.getDuration("ssl-context-cache-ttl").toMillis.millis
  private val contextRef = new AtomicReference[Option[Cache]](None)
  private def getCache: Cache = {
    contextRef.get() match {
      case Some(cache :Cache) if cache.expires.hasTimeLeft() =>
        contextRef.get().get
      case _ =>
        // TODO: use "SecureRandomFactory.createSecureRandom(SSLRandomNumberGenerator, log)" instead,
        val rng = new SecureRandom()
        // PemManagersProvider loads certificates only at construction time.
        val managersProvider = new PemManagersProvider(config)
        val factory = new SslFactory(config, managersProvider, rng)(log)
        val sessionVerifier: SessionVerifier = new PeerSubjectVerifier(managersProvider.peerCertificate)
        val cache = new Cache(factory, sessionVerifier, SSLContextCacheTime.fromNow)
        contextRef.set(Some(cache))
        cache
    }
  }

  override def createServerSSLEngine(hostname: String, port: Int): SSLEngine =
    getCache.sslFactory.createServerSSLEngine(hostname, port)

  override def createClientSSLEngine(hostname: String, port: Int): SSLEngine =
    getCache.sslFactory.createClientSSLEngine(hostname, port)

  override def verifyClientSession(hostname: String, session: SSLSession): Option[Throwable] =
    getCache.sessionVerifier.verifyClientSession(hostname, session)

  override def verifyServerSession(hostname: String, session: SSLSession): Option[Throwable] =
    getCache.sessionVerifier.verifyServerSession(hostname, session)

}

/**
 * INTERNAL API
 */
@InternalApi
private[ssl] class Cache(val sslFactory: SslFactory,
                          val sessionVerifier: SessionVerifier,
                          val expires: Deadline)
