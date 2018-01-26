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
import java.security.Principal
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLPeerUnverifiedException
import javax.security.auth.kerberos.KerberosPrincipal

import scala.annotation.tailrec
import scala.util.Try

import akka.actor.ActorSystem
import akka.annotation.ApiMayChange
import akka.annotation.InternalApi
import akka.event.LogMarker
import akka.event.Logging
import akka.event.MarkerLoggingAdapter
import akka.japi.Util.immutableSeq
import akka.remote.security.provider.AkkaProvider
import akka.stream.ConnectionException
import akka.stream.IgnoreComplete
import akka.stream.TLSClosing
import akka.stream.TLSRole
import com.typesafe.config.Config
import com.typesafe.sslconfig.Base64
import sun.security.util.HostnameChecker

@ApiMayChange trait SSLEngineProvider {

  def createServerSSLEngine(hostname: String, port: Int): SSLEngine

  def createClientSSLEngine(hostname: String, port: Int): SSLEngine

  /**
   * Verification that will be called after every successful handshake
   * to verify additional session information. Return `None` if valid
   * otherwise `Some` with explaining cause.
   */
  def verifyClientSession(hostname: String, session: SSLSession): Option[Throwable]

  /**
   * Verification that will be called after every successful handshake
   * to verify additional session information. Return `None` if valid
   * otherwise `Some` with explaining cause.
   */
  def verifyServerSession(hostname: String, session: SSLSession): Option[Throwable]

}

class SslTransportException(message: String, cause: Throwable) extends RuntimeException(message, cause)

/**
 * INTERNAL API: only public via config
 * Config in
 */
@InternalApi private[akka] final class ConfigSSLEngineProvider(config: Config, log: MarkerLoggingAdapter) extends SSLEngineProvider {

  def this(system: ActorSystem) = this(
    system.settings.config.getConfig("akka.remote.artery.ssl.config-ssl-engine"),
    Logging.withMarker(system, classOf[ConfigSSLEngineProvider].getName))

  private val SSLKeyStore = config.getString("key-store")
  private val SSLTrustStore = config.getString("trust-store")
  private val SSLKeyStorePassword = config.getString("key-store-password")
  private val SSLKeyPassword = config.getString("key-password")
  private val SSLTrustStorePassword = config.getString("trust-store-password")
  val SSLEnabledAlgorithms = immutableSeq(config.getStringList("enabled-algorithms")).to[Set]
  val SSLProtocol = config.getString("protocol")
  val SSLRandomNumberGenerator = config.getString("random-number-generator")
  val SSLRequireMutualAuthentication = config.getBoolean("require-mutual-authentication")

  private val hostnameVerifier: HostnameVerifier = config.getBoolean("hostname-verification") match {
    case false ⇒ new DisabledComplainingHostnameVerifier(log) // FIXME is this a good default or should we have a completely silent default?
    case true  ⇒ new DefaultHostnameVerifier(log)
  }

  private val sslContext = new AtomicReference[SSLContext]()

  @tailrec final def getOrCreateContext(): SSLContext = {
    sslContext.get() match {
      case null ⇒
        val newCtx = constructContext()
        if (sslContext.compareAndSet(null, newCtx)) newCtx
        else getOrCreateContext()
      case ctx ⇒ ctx
    }
  }

  private def constructContext(): SSLContext = {
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

  override def createServerSSLEngine(hostname: String, port: Int): SSLEngine =
    createSSLEngine(akka.stream.Server, hostname, port)

  override def createClientSSLEngine(hostname: String, port: Int): SSLEngine =
    createSSLEngine(akka.stream.Client, hostname, port)

  private def createSSLEngine(role: TLSRole, hostname: String, port: Int): SSLEngine = {
    createSSLEngine(getOrCreateContext(), role, hostname, port)
  }

  private def createSSLEngine(
    sslContext: SSLContext,
    role:       TLSRole,
    hostname:   String,
    port:       Int,
    closing:    TLSClosing = IgnoreComplete): SSLEngine = {

    val engine = sslContext.createSSLEngine(hostname, port)
    engine.setUseClientMode(role == akka.stream.Client)
    engine.setEnabledCipherSuites(SSLEnabledAlgorithms.toArray)
    engine.setEnabledProtocols(Array(SSLProtocol))

    if ((role != akka.stream.Client) && SSLRequireMutualAuthentication)
      engine.setNeedClientAuth(true)

    engine
  }

  override def verifyClientSession(hostname: String, session: SSLSession): Option[Throwable] =
    verifyHostname(hostname, session)

  override def verifyServerSession(hostname: String, session: SSLSession): Option[Throwable] =
    verifyHostname(hostname, session)

  private def verifyHostname(hostname: String, session: SSLSession): Option[Throwable] = {
    if (hostnameVerifier.verify(hostname, session)) None
    else Some(new ConnectionException(s"Hostname verification failed! Expected session to be for [$hostname]"))
  }

}

/**
 * INTERNAL API
 *
 * A disabled but complaining hostname verifier.
 *
 * Copied this from ssl-config to avoid the "dependency" and adjust logging.
 * TODO perhaps this should be public?
 */
@InternalApi private[akka] class DisabledComplainingHostnameVerifier(log: MarkerLoggingAdapter) extends HostnameVerifier {

  private val defaultHostnameVerifier = new DefaultHostnameVerifier(log)

  override def verify(hostname: String, sslSession: SSLSession): Boolean = {
    val hostNameMatches = defaultHostnameVerifier.verify(hostname, sslSession)
    if (!hostNameMatches) {
      log.warning(LogMarker.Security, "Hostname verification failed on hostname [{}], " +
        "but the connection was accepted because hostname-verification is disabled. " +
        "Please fix the X.509 certificate on the host to remove this warning. " +
        "More details can be seen by enabling debug logging.", hostname)
    }
    true
  }
}

/**
 * INTERNAL API
 *
 * Use the internal sun hostname checker as the hostname verifier. Thanks to Kevin Locke.
 *
 * Copied this from ssl-config to avoid the "dependency" and adjust logging.
 * TODO perhaps this should be public?
 *
 * @see sun.security.util.HostnameChecker
 * @see http://kevinlocke.name/bits/2012/10/03/ssl-certificate-verification-in-dispatch-and-asynchttpclient/
 */
@InternalApi private[akka] class DefaultHostnameVerifier(log: MarkerLoggingAdapter) extends HostnameVerifier {

  // AsyncHttpClient issue #197: "SSL host name verification disabled by default"
  // https://github.com/AsyncHttpClient/async-http-client/issues/197
  //
  // From http://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html#HostnameVerifier
  //
  // "When using raw SSLSockets/SSLEngines you should always check the peer's credentials before sending any data.
  // The SSLSocket and SSLEngine classes do not automatically verify that the hostname in a URL matches the
  // hostname in the peer's credentials. An application could be exploited with URL spoofing if the hostname is
  // not verified."
  //
  // We are using SSLEngine directly, so we have to use the AsyncHttpClient Netty Provider to provide hostnname
  // verification.

  def hostnameChecker: HostnameChecker = HostnameChecker.getInstance(HostnameChecker.TYPE_TLS)

  def matchKerberos(hostname: String, principal: Principal) = HostnameChecker.`match`(hostname, principal.asInstanceOf[KerberosPrincipal])

  def isKerberos(principal: Principal): Boolean = principal != null && principal.isInstanceOf[KerberosPrincipal]

  def verify(hostname: String, session: SSLSession): Boolean = {
    val base64 = Base64.rfc2045()

    val result = try {
      val peerCertificates = session.getPeerCertificates
      matchCertificates(hostname, session, peerCertificates, base64)
    } catch {
      case _: SSLPeerUnverifiedException ⇒
        // Not using certificates for verification, try verifying the principal
        try {
          val principal = session.getPeerPrincipal
          if (isKerberos(principal)) {
            matchKerberos(hostname, principal)
          } else {
            // Can't verify principal, not Kerberos
            if (log.isDebugEnabled)
              log.debug(
                "verify: hostname [{}], sessionId (base64) [{}], Can't verify principal, not Kerberos",
                hostname, base64.encodeToString(session.getId, false))
            false
          }
        } catch {
          case e: SSLPeerUnverifiedException ⇒
            // Can't verify principal, no principal
            if (log.isDebugEnabled)
              log.debug(
                "verify: hostname [{}], sessionId (base64) [{}], Can't verify principal, no principal. Cause: {}",
                hostname, base64.encodeToString(session.getId, false), e)
            false
        }
    }
    if (log.isDebugEnabled)
      log.debug(
        "verify: hostname [{}], sessionId (base64) [{}], result [{}]",
        hostname, base64.encodeToString(session.getId, false), result)
    result
  }

  private def matchCertificates(hostname: String, session: SSLSession, peerCertificates: Array[Certificate],
                                base64: Base64): Boolean = {
    val checker = hostnameChecker

    peerCertificates match {
      case Array(cert: X509Certificate, _*) ⇒
        try {
          checker.`match`(hostname, cert)
          // Certificate matches hostname
          true
        } catch {
          case e: CertificateException ⇒
            // Certificate does not match hostname
            val subjectAltNames = cert.getSubjectAlternativeNames
            if (log.isDebugEnabled)
              log.debug(
                "verify: hostname [{}], sessionId (base64) [{}], Certificate does not match hostname! " +
                  "subjectAltNames [{}], Cause: {}",
                hostname, base64.encodeToString(session.getId, false), subjectAltNames, e)
            false
        }

      case notMatch ⇒
        // Peer does not have any certificates or they aren't X.509
        if (log.isDebugEnabled)
          log.debug(
            "verify: hostname [{}], sessionId (base64) [{}], Peer does not have any certificates: {}",
            hostname, base64.encodeToString(session.getId, false), notMatch)
        false
    }
  }
}
