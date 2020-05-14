/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp.ssl

import java.security.SecureRandom

import akka.actor.ActorSystem
import akka.event.Logging
import akka.event.MarkerLoggingAdapter
import akka.remote.artery.tcp.SSLEngineProvider
import com.typesafe.config.Config
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession

// TODO: rename this class (rename the settings namespace!)
final class TlsMagicSSLEngineProvider(protected val config: Config, protected val log: MarkerLoggingAdapter)
    extends SSLEngineProvider {

  def this(system: ActorSystem) =
    this(
      // TODO: when renaming the class, review the namespace in config
      system.settings.config.getConfig("akka.remote.artery.ssl.tls-magic-engine"),
      Logging.withMarker(system, classOf[TlsMagicSSLEngineProvider].getName))

  val rng = new SecureRandom()
  def providerFactory: (Config) => SslManagersProvider = new PemManagersProvider(_)
  val sessionVerifierFactory: SslManagersProvider => SessionVerifier = smp =>
    new PeerSubjectVerifier(smp.nodeCertificate)
  val sslFactory = new SslFactory(config, providerFactory, rng, sessionVerifierFactory)(log)

  override def createServerSSLEngine(hostname: String, port: Int): SSLEngine =
    sslFactory.createServerSSLEngine(hostname, port)

  override def createClientSSLEngine(hostname: String, port: Int): SSLEngine =
    sslFactory.createClientSSLEngine(hostname, port)

  override def verifyClientSession(hostname: String, session: SSLSession): Option[Throwable] =
    sslFactory.sessionVerifier.verifyClientSession(hostname, session)

  override def verifyServerSession(hostname: String, session: SSLSession): Option[Throwable] =
    sslFactory.sessionVerifier.verifyServerSession(hostname, session)

}
