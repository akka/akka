/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp.ssl

import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

import akka.annotation.InternalApi
import akka.event.LogMarker
import akka.event.MarkerLoggingAdapter
import akka.japi.Util.immutableSeq
import akka.remote.artery.tcp.SslTransportException
import com.typesafe.config.Config
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

/**
 * TODO: I'm not sure this class should be private. Users may write their SSLEngineProviders and use it.
 * INTERNAL API
 */
@InternalApi
private[tcp] final class SslFactory(
    config: Config,
    sslManagersProviderFactory: (Config) => SslManagersProvider,
    prng: SecureRandom,
    sessionVerifierFactory: SslManagersProvider => SessionVerifier)(log: MarkerLoggingAdapter) {

  private val SSLProtocol: String = config.getString("protocol")
  private val SSLEnabledAlgorithms: Set[String] =
    immutableSeq(config.getStringList("enabled-algorithms")).toSet
  private val SSLRequireMutualAuthentication: Boolean = config.getBoolean("require-mutual-authentication")
  private val HostnameVerification: Boolean = config.getBoolean("hostname-verification")
  private val SSLContextCacheTime: FiniteDuration =
    config.getDuration("ssl-context-cache-ttl").toMillis.millis

  // log hostname verification warning once
  if (HostnameVerification)
    log.debug("TLS/SSL hostname verification is enabled.")
  else
    log.info(
      LogMarker.Security,
      "TLS/SSL hostname verification is disabled. See Akka reference documentation for more information.")

  def sessionVerifier: SessionVerifier = getCache.sessionVerifier

  def sslContext: SSLContext = getCache.sslContext
  private val contextRef = new AtomicReference[Option[Cache]](None)
  private def getCache: Cache = {
    contextRef.get() match {
      case Some(cache: Cache) if cache.expires.hasTimeLeft() =>
        contextRef.get().get
      case _ =>
        val managersProvider: SslManagersProvider = sslManagersProviderFactory(config)
        val sessionVerifier: SessionVerifier = sessionVerifierFactory(managersProvider)
        val context = constructContext(managersProvider)
        val cache = new Cache(context, managersProvider, sessionVerifier, SSLContextCacheTime.fromNow)
        contextRef.set(Some(cache))
        cache
    }
  }

  private def constructContext(managersProvider: SslManagersProvider) = {
    try {
      val ctx: SSLContext = SSLContext.getInstance(SSLProtocol)
      ctx.init(managersProvider.keyManagers, managersProvider.trustManagers, prng)
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
    requireMutualAuthentication
  private val forClient: SSLEngine => SSLEngine =
    setClientMode.andThen(withHostnameVerification)

  // Server-side
  private def requireMutualAuthentication: SSLEngine => SSLEngine = { engine =>
    if (SSLRequireMutualAuthentication) engine.setNeedClientAuth(true)
    else
      log.info(
        LogMarker.Security,
        "mTLS (aka TLS Client Authentication) is disabled. See Akka reference documentation for more information.")
    engine
  }

  //Client-side
  private def setClientMode: SSLEngine => SSLEngine = { engine =>
    engine.setUseClientMode(true)
    engine
  }

  //Client-side
  private def withHostnameVerification: SSLEngine => SSLEngine = { engine =>
    if (HostnameVerification) {
      val sslParams = sslContext.getDefaultSSLParameters
      sslParams.setEndpointIdentificationAlgorithm("HTTPS")
      engine.setSSLParameters(sslParams)
    }
    engine
  }

}

/**
 * INTERNAL API
 */
@InternalApi
private[ssl] class Cache(
    val sslContext: SSLContext,
    val sslManagersProvider: SslManagersProvider,
    val sessionVerifier: SessionVerifier,
    val expires: Deadline)
