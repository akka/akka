package akka.remote.artery.tcp.ssl

import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom

import akka.annotation.InternalApi
import akka.event.LogMarker
import akka.event.MarkerLoggingAdapter
import akka.japi.Util.immutableSeq
import akka.remote.artery.tcp.SslTransportException
import com.typesafe.config.Config
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine

/**
 * TODO: I'm not sure this class should be private. Users may write their SSLEngineProviders and use it.
 * INTERNAL API
 */
@InternalApi
private[tcp] final class SslFactory(
    config: Config,
// This is a val since it's useful to traceback from an SSLContext to the SslManagersProvider used to create it.
    val sslManagersProvider: SslManagersProvider,
    prng: SecureRandom)(log: MarkerLoggingAdapter) {

  private val SSLProtocol: String = config.getString("protocol")

  private val SSLEnabledAlgorithms: Set[String] =
    immutableSeq(config.getStringList("enabled-algorithms")).toSet
  private val SSLRequireMutualAuthentication: Boolean = config.getBoolean("require-mutual-authentication")
  private val HostnameVerification: Boolean = config.getBoolean("hostname-verification")

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
    if (SSLRequireMutualAuthentication) engine.setNeedClientAuth(true)
    else
      log.info(LogMarker.Security,
        "mTLS (aka TLS Client Authentication) is disabled. See Akka reference documentation for more information.")
    engine
  }

  //Client-side
  private def setClientMode()(engine: SSLEngine): SSLEngine = {
    engine.setUseClientMode(true)
    engine
  }

  //Client-side
  private def withHostnameVerification()(engine: SSLEngine): SSLEngine = {
    if (HostnameVerification) {
      val sslParams = sslContext.getDefaultSSLParameters
      sslParams.setEndpointIdentificationAlgorithm("HTTPS")
      engine.setSSLParameters(sslParams)
    }
    engine
  }

}
