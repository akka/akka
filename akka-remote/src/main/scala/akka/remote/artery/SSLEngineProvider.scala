/**
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory

import scala.annotation.tailrec
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import akka.NotUsed
import akka.actor.ActorSystem
import akka.annotation.InternalApi
import akka.event.LogMarker
import akka.event.Logging
import akka.event.MarkerLoggingAdapter
import akka.japi.Util.immutableSeq
import akka.remote.RemoteTransportException
import akka.remote.security.provider.AkkaProvider
import akka.stream.Attributes
import akka.stream.ConnectionException
import akka.stream.IgnoreComplete
import akka.stream.TLSClosing
import akka.stream.TLSProtocol.NegotiateNewSession
import akka.stream.TLSProtocol.SslTlsInbound
import akka.stream.TLSProtocol.SslTlsOutbound
import akka.stream.TLSRole
import akka.stream.impl.io.TlsModule
import akka.stream.impl.io.TlsUtils
import akka.stream.scaladsl
import akka.util.ByteString
import com.typesafe.sslconfig.akka.AkkaSSLConfig

trait SSLEngineProvider {

  def createServerSSLEngine(): SSLEngine

  // FIXME targetHost parameter?
  def createClientSSLEngine(): SSLEngine
}

class SslTransportException(message: String, cause: Throwable) extends RuntimeException(message, cause)

/**
 * INTERNAL API: only public via config
 */
@InternalApi private[akka] final class ConfigSSLEngineProvider(system: ActorSystem) extends SSLEngineProvider {

  private val config = system.settings.config.getConfig("akka.remote.artery.ssl")

  private val SSLKeyStore = config.getString("key-store")
  private val SSLTrustStore = config.getString("trust-store")
  private val SSLKeyStorePassword = config.getString("key-store-password")
  private val SSLKeyPassword = config.getString("key-password")
  private val SSLTrustStorePassword = config.getString("trust-store-password")
  private val SSLEnabledAlgorithms = immutableSeq(config.getStringList("enabled-algorithms")).to[Set]
  private val SSLProtocol = config.getString("protocol")
  private val SSLRandomNumberGenerator = config.getString("random-number-generator")
  private val SSLRequireMutualAuthentication = config.getBoolean("require-mutual-authentication")

  private val log: MarkerLoggingAdapter = Logging.withMarker(system, getClass.getName)

  private val sslContext = new AtomicReference[SSLContext]()

  @tailrec final def getOrCreateContext(): SSLContext = {
    sslContext.get() match {
      case null ⇒
        val newCtx = constructContext(log)
        if (sslContext.compareAndSet(null, newCtx)) newCtx
        else getOrCreateContext()
      case ctx ⇒ ctx
    }
  }

  private def constructContext(log: MarkerLoggingAdapter): SSLContext = {
    try {
      def loadKeystore(filename: String, password: String): KeyStore = {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
        val fin = Files.newInputStream(Paths.get(filename))
        try keyStore.load(fin, password.toCharArray) finally Try(fin.close())
        keyStore
      }

      val keyManagers = {
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
        factory.init(loadKeystore(SSLKeyStore, SSLKeyStorePassword), SSLKeyPassword.toCharArray)
        factory.getKeyManagers
      }
      val trustManagers = {
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
        trustManagerFactory.init(loadKeystore(SSLTrustStore, SSLTrustStorePassword))
        trustManagerFactory.getTrustManagers
      }
      val rng = createSecureRandom()

      val ctx = SSLContext.getInstance(SSLProtocol)
      ctx.init(keyManagers, trustManagers, rng)
      ctx
    } catch {
      case e: FileNotFoundException ⇒
        throw new SslTransportException("Server SSL connection could not be established because key store could not be loaded", e)
      case e: IOException ⇒
        throw new SslTransportException("Server SSL connection could not be established because: " + e.getMessage, e)
      case e: GeneralSecurityException ⇒
        throw new SslTransportException("Server SSL connection could not be established because SSL context could not be constructed", e)
    }
  }

  def createSecureRandom(): SecureRandom = {
    val rng = SSLRandomNumberGenerator match {
      case r @ ("AES128CounterSecureRNG" | "AES256CounterSecureRNG") ⇒
        log.debug("SSL random number generator set to: {}", r)
        SecureRandom.getInstance(r, AkkaProvider)
      case s @ ("SHA1PRNG" | "NativePRNG") ⇒
        log.debug("SSL random number generator set to: {}", s)
        // SHA1PRNG needs /dev/urandom to be the source on Linux to prevent problems with /dev/random blocking
        // However, this also makes the seed source insecure as the seed is reused to avoid blocking (not a problem on FreeBSD).
        SecureRandom.getInstance(s)

      case "" ⇒
        log.debug("SSLRandomNumberGenerator not specified, falling back to SecureRandom")
        new SecureRandom

      case unknown ⇒
        log.warning(LogMarker.Security, "Unknown SSLRandomNumberGenerator [{}] falling back to SecureRandom", unknown)
        new SecureRandom
    }
    rng.nextInt() // prevent stall on first access
    rng
  }

  private def createSSLEngine(role: TLSRole): SSLEngine = {
    createSSLEngine(getOrCreateContext(), sslConfig = None, role)
  }

  override def createServerSSLEngine(): SSLEngine =
    createSSLEngine(akka.stream.Server)

  override def createClientSSLEngine(): SSLEngine =
    createSSLEngine(akka.stream.Client)

  private def createSSLEngine(
    sslContext: SSLContext,
    sslConfig:  Option[AkkaSSLConfig],
    role:       TLSRole,
    closing:    TLSClosing            = IgnoreComplete,
    hostInfo:   Option[(String, Int)] = None): SSLEngine = {

    /* FIXME this is what NettySSLSupport does
    val sslEngine = settings.getOrCreateContext(log).createSSLEngine // TODO: pass host information to enable host verification
    sslEngine.setUseClientMode(isClient)
    sslEngine.setEnabledCipherSuites(settings.SSLEnabledAlgorithms.toArray)
    sslEngine.setEnabledProtocols(Array(settings.SSLProtocol))
    */

    // below is based on how stream.TLS creates SSLEngine from SSLContext

    val firstSession = NegotiateNewSession.withCipherSuites(SSLEnabledAlgorithms.toSeq: _*)

    def theSslConfig(system: ActorSystem): AkkaSSLConfig =
      sslConfig.getOrElse(AkkaSSLConfig(system))

    val engine = hostInfo match {
      case Some((hostname, port)) ⇒ sslContext.createSSLEngine(hostname, port)
      case None                   ⇒ sslContext.createSSLEngine()
    }
    val config = theSslConfig(system)
    config.sslEngineConfigurator.configure(engine, sslContext)

    engine.setUseClientMode(role == akka.stream.Client)

    // FIXME took this from NettySSLSupport, still valid?
    if ((role != akka.stream.Client) && SSLRequireMutualAuthentication) engine.setNeedClientAuth(true)

    val finalSessionParameters =
      if (firstSession.sslParameters.isDefined && hostInfo.isDefined && !config.config.loose.disableSNI) {
        val newParams = TlsUtils.cloneParameters(firstSession.sslParameters.get)
        // In Java 7, SNI was automatically enabled by enabling "jsse.enableSNIExtension" and using
        // `createSSLEngine(hostname, port)`.
        // In Java 8, SNI is only enabled if the server names are added to the parameters.
        // See https://github.com/akka/akka/issues/19287.
        newParams.setServerNames(Collections.singletonList(new SNIHostName(hostInfo.get._1)))
        firstSession.copy(sslParameters = Some(newParams))
      } else
        firstSession

    TlsUtils.applySessionParameters(engine, finalSessionParameters)

    /* FIXME what about this, parameter to TlsModule?
    def verifySession: (ActorSystem, SSLSession) ⇒ Try[Unit] =
      hostInfo match {
        case Some((hostname, _)) ⇒ { (system, session) ⇒
          val hostnameVerifier = theSslConfig(system).hostnameVerifier
          if (!hostnameVerifier.verify(hostname, session))
            Failure(new ConnectionException(s"Hostname verification failed! Expected session to be for $hostname"))
          else
            Success(())
        }
        case None ⇒ (_, _) ⇒ Success(())
      }
    */

    engine
  }

}
