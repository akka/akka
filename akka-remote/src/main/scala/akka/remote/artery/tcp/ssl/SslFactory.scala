package akka.remote.artery.tcp.ssl

import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom

import akka.event.LogMarker
import akka.event.MarkerLoggingAdapter
import akka.japi.Util.immutableSeq
import akka.remote.artery.tcp.SslTransportException
import com.typesafe.config.Config
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine

private[tcp] class SslFactory(config: Config, val sslManagersProvider: SslManagersProvider, prng: SecureRandom)(
    protected val log: MarkerLoggingAdapter) {

  val SSLProtocol: String = config.getString("protocol")

  val SSLEnabledAlgorithms: Set[String] =
    immutableSeq(config.getStringList("enabled-algorithms")).toSet
  val SSLRequireMutualAuthentication: Boolean = config.getBoolean("require-mutual-authentication")
  val HostnameVerification: Boolean = config.getBoolean("hostname-verification")

  // log hostname verification warning once
  if (HostnameVerification)
    log.debug("TLS/SSL hostname verification is enabled.")
  else
    log.info(
      LogMarker.Security,
      "TLS/SSL hostname verification is disabled. See Akka reference documentation for more information.")

  val sslContext: SSLContext = {
    try {
      val rng = prng
      val ctx: SSLContext = SSLContext.getInstance(SSLProtocol)
      ctx.init(sslManagersProvider.keyManagers, sslManagersProvider.trustManagers, rng)
      ctx
    } catch {
      case e: FileNotFoundException =>
        throw new SslTransportException(
          "Server SSL connection could not be established because key store could not be loaded",
          e)
      case e: IOException =>
        throw new SslTransportException("Server SSL connection could not be established because: " + e.getMessage, e)
      case e: GeneralSecurityException =>
        throw new SslTransportException(
          "Server SSL connection could not be established because SSL context could not be constructed",
          e)
    }
  }

  private[tcp] def createServerSSLEngine(hostname: String, port: Int): SSLEngine =
    forServer(createSSLEngine(hostname, port))

  private[tcp] def createClientSSLEngine(hostname: String, port: Int): SSLEngine =
    forClient(createSSLEngine(hostname, port))

  // 1. create an engine
  private[tcp] def createSSLEngine(hostname: String, port: Int): SSLEngine = {
    val engine = sslContext.createSSLEngine(hostname, port)
    engine.setEnabledCipherSuites(SSLEnabledAlgorithms.toArray)
    engine.setEnabledProtocols(Array(SSLProtocol))
    engine
  }

  // 2. customize the engine depending on the peer role
  private val forServer: SSLEngine => SSLEngine =
    requireMutualAuthentication()
  private val forClient: SSLEngine => SSLEngine =
    setClientMode().andThen(withHostnameVerification())

  // Server-side
  private def requireMutualAuthentication()(engine: SSLEngine): SSLEngine = {
    // TODO: warn when mTLS is disabled
    if (SSLRequireMutualAuthentication) engine.setNeedClientAuth(true)
    engine
  }

  //Client-side
  private def setClientMode()(engine: SSLEngine): SSLEngine = {
    engine.setUseClientMode(true)
    engine
  }

  //Client-side
  private def withHostnameVerification()(engine: SSLEngine): SSLEngine = {
    // TODO: validate subject-based authorization is disabled.
    if (HostnameVerification) {
      val sslParams = sslContext.getDefaultSSLParameters
      sslParams.setEndpointIdentificationAlgorithm("HTTPS")
      engine.setSSLParameters(sslParams)
    }
    engine
  }

}
