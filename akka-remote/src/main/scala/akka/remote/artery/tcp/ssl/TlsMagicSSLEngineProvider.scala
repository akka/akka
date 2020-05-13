package akka.remote.artery.tcp.ssl

import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorSystem
import akka.annotation.InternalApi
import akka.event.Logging
import akka.event.MarkerLoggingAdapter
import akka.remote.artery.tcp.SSLEngineProvider
import akka.remote.artery.tcp.SecureRandomFactory
import com.typesafe.config.Config
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession

import scala.concurrent.duration._

// TODO: rename this class (rename the settings namespace!)
final class TlsMagicSSLEngineProvider(protected val config: Config, protected val log: MarkerLoggingAdapter)
    extends SSLEngineProvider {

  def this(system: ActorSystem) =
    this(
      system.settings.config
      // TODO: when renaming the class, review the namespace in config
        .getConfig("akka.remote.artery.ssl.tls-magic-engine"),
      Logging.withMarker(system, classOf[TlsMagicSSLEngineProvider].getName))

  // Cache support
  private val SSLContextCacheTime: FiniteDuration =
    config.getDuration("ssl-context-cache-ttl").toMillis.millis
  private val contextRef = new AtomicReference[Option[Cache]](None)
  private def getFactory: SslFactory = getCache.sslFactory
  private def getCache: Cache = {
    contextRef.get() match {
      case Some(Cache(_, expired)) if expired.timeLeft =>
        contextRef.get().get
      case _ =>
        // RNG support
        // TODO: use "SecureRandomFactory.createSecureRandom(SSLRandomNumberGenerator, log)" instead,
        //  I'm just trying to demonstrate usage of different implementations
        val rng = new SecureRandom()
        // This line is where all pieces are instantiated and put together.
        val context = new SslFactory(config, new PemManagersProvider(config), rng)(log)
        val cache = Cache(context, SSLContextCacheTime.fromNow)
        contextRef.set(Some(cache))
        cache
    }
  }

  override def createServerSSLEngine(hostname: String, port: Int): SSLEngine =
    getFactory.createClientSSLEngine(hostname, port)

  override def createClientSSLEngine(hostname: String, port: Int): SSLEngine =
    getFactory.createClientSSLEngine(hostname, port)

  override def verifyClientSession(hostname: String, session: SSLSession): Option[Throwable] =
    getCache.sessionVerifier.verifyClientSession(hostname, session)

  override def verifyServerSession(hostname: String, session: SSLSession): Option[Throwable] =
    getCache.sessionVerifier.verifyServerSession(hostname, session)

}

/**
 * INTERNAL API
 */
@InternalApi
private[ssl] case class Cache(sslFactory: SslFactory, expires: Deadline) {
  val sslContext: SSLContext = sslFactory.sslContext
  val peerCertificate: X509Certificate = sslFactory.sslManagersProvider.peerCertificate
  val sessionVerifier: PeerSubjectVerifier = new PeerSubjectVerifier(sslFactory.sslManagersProvider.peerCertificate)
}
