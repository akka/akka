/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp.ssl

import java.security.SecureRandom

import akka.actor.ActorSystem
import akka.event.Logging
import akka.event.MarkerLoggingAdapter
import akka.remote.artery.tcp.SSLEngineProvider
import akka.remote.artery.tcp.SecureRandomFactory
import com.typesafe.config.Config
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession

/**
 * Config in akka.remote.artery.ssl.config-ssl-engine
 */
final class ConfigSSLEngineProvider(protected val config: Config, protected val log: MarkerLoggingAdapter)
    extends SSLEngineProvider {

  def this(system: ActorSystem) =
    this(
      system.settings.config.getConfig("akka.remote.artery.ssl.config-ssl-engine"),
      Logging.withMarker(system, classOf[ConfigSSLEngineProvider].getName))

  private val rng: SecureRandom = SecureRandomFactory.createSecureRandom(config, log)
  // Creating a new factory requires creating a new managersProvider so certificates are freshly read.
  def providerFactory: (Config) => SslManagersProvider = new JksManagersProvider(_)
  val sessionVerifierFactory: SslManagersProvider => SessionVerifier = _ => NoopSessionVerifier
  private lazy val sslFactory: SslFactory = new SslFactory(config, providerFactory, rng, sessionVerifierFactory)(log)

  override def createServerSSLEngine(hostname: String, port: Int): SSLEngine =
    sslFactory.createServerSSLEngine(hostname, port)

  override def createClientSSLEngine(hostname: String, port: Int): SSLEngine =
    sslFactory.createClientSSLEngine(hostname, port)

  override def verifyClientSession(hostname: String, session: SSLSession): Option[Throwable] =
    sslFactory.sessionVerifier.verifyClientSession(hostname, session)

  override def verifyServerSession(hostname: String, session: SSLSession): Option[Throwable] =
    sslFactory.sessionVerifier.verifyServerSession(hostname, session)
}
