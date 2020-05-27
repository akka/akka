/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp.ssl

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorSystem
import akka.event.Logging
import akka.event.MarkerLoggingAdapter
import akka.pki.pem.DERPrivateKeyLoader
import akka.pki.pem.PEMDecoder
import akka.remote.artery.tcp.SSLEngineProvider
import akka.remote.artery.tcp.SecureRandomFactory
import akka.remote.artery.tcp.SslTransportException
import akka.remote.artery.tcp.ssl.TlsMagicSSLEngineProvider.CachedContext
import akka.remote.artery.tcp.ssl.TlsMagicSSLEngineProvider.ConfiguredContext
import akka.stream.TLSRole
import com.typesafe.config.Config
import javax.naming.ldap.LdapName
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory

import scala.collection.JavaConverters._
import scala.concurrent.duration._

final class TlsMagicSSLEngineProvider(val config: Config, protected val log: MarkerLoggingAdapter)
    extends SSLEngineProvider {

  def this(system: ActorSystem) =
    this(
      system.settings.config
        .getConfig("akka.remote.artery.ssl.tls-magic-engine"),
      Logging.withMarker(system, classOf[ConfigSSLEngineProvider].getName))

  private val contextRef = new AtomicReference[Option[CachedContext]](None)

  private def getContext() = {
    contextRef.get() match {
      case Some(CachedContext(_, expired)) if expired.isOverdue() =>
        val context = constructContext()
        contextRef.set(
          Some(CachedContext(context, SSLContextCacheTime.fromNow))
        )
        context
      case Some(CachedContext(cached, _)) => cached
      case None =>
        val context = constructContext()
        contextRef.set(
          Some(CachedContext(context, SSLContextCacheTime.fromNow))
        )
        context
    }
  }

  private val rng: SecureRandom = SecureRandomFactory.createSecureRandom(config, log)

  private val SSLKeyFile: String = config.getString("key-file")
  private val SSLCertFile: String = config.getString("cert-file")
  private val SSLCACertFile: String = config.getString("ca-cert-file")
  private val SSLContextCacheTime: FiniteDuration =
    config.getDuration("ssl-context-cache-ttl").toMillis.millis
  private val SSLEnabledAlgorithms: Set[String] =
    config.getStringList("enabled-algorithms").asScala.toSet
  private val SSLProtocol: String = config.getString("protocol")

  private def constructContext(): ConfiguredContext = {
    try {
      val certFactory = CertificateFactory.getInstance("X.509")
      val cert = certFactory
        .generateCertificate(new FileInputStream(SSLCertFile))
        .asInstanceOf[X509Certificate]
      val cacert =
        certFactory.generateCertificate(new FileInputStream(SSLCACertFile))

      val keyStore = KeyStore.getInstance("JKS")
      keyStore.load(null)
      // Load the private key
      val privateKey = loadPrivateKey(SSLKeyFile)

      keyStore.setCertificateEntry("cert", cert)
      keyStore.setCertificateEntry("cacert", cacert)
      keyStore.setKeyEntry(
        "private-key",
        privateKey,
        "changeit".toCharArray,
        Array(cert, cacert)
      )

      val kmf =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
      kmf.init(keyStore, "changeit".toCharArray)
      val keyManagers = kmf.getKeyManagers

      val trustStore = KeyStore.getInstance("JKS")
      trustStore.load(null)
      trustStore.setCertificateEntry("cacert", cacert)

      val tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      tmf.init(trustStore)
      val trustManagers = tmf.getTrustManagers

      val subjectNames = getAllSubjectNames(cert)

      val ctx = SSLContext.getInstance(SSLProtocol)
      ctx.init(keyManagers, trustManagers, rng)
      ConfiguredContext(ctx, subjectNames)
    } catch {
      case e: FileNotFoundException =>
        throw new SslTransportException(
          "Server SSL connection could not be established because a key or cert could not be loaded",
          e
        )
      case e: IOException =>
        throw new SslTransportException(
          "Server SSL connection could not be established because: " + e.getMessage,
          e
        )
      case e: GeneralSecurityException =>
        throw new SslTransportException(
          "Server SSL connection could not be established because SSL context could not be constructed",
          e
        )
      case e: IllegalArgumentException =>
        throw new SslTransportException(
          "Server SSL connection could not be established because: " + e.getMessage,
          e
        )
    }
  }

  private def loadPrivateKey(filename: String): PrivateKey = {
    val bytes = Files.readAllBytes(new File(filename).toPath)
    val pemData = new String(bytes, Charset.forName("UTF-8"))
    DERPrivateKeyLoader.load(PEMDecoder.decode(pemData))
  }


  override def createServerSSLEngine(hostname: String, port: Int): SSLEngine =
    createSSLEngine(akka.stream.Server, hostname, port)

  override def createClientSSLEngine(hostname: String, port: Int): SSLEngine =
    createSSLEngine(akka.stream.Client, hostname, port)

  private def createSSLEngine(role: TLSRole,
                              hostname: String,
                              port: Int): SSLEngine = {
    createSSLEngine(getContext().context, role, hostname, port)
  }

  private def createSSLEngine(sslContext: SSLContext,
                              role: TLSRole,
                              hostname: String,
                              port: Int): SSLEngine = {

    val engine = sslContext.createSSLEngine(hostname, port)

    engine.setUseClientMode(role == akka.stream.Client)
    engine.setEnabledCipherSuites(SSLEnabledAlgorithms.toArray)
    engine.setEnabledProtocols(Array(SSLProtocol))

    if (role != akka.stream.Client) engine.setNeedClientAuth(true)

    engine
  }

  override def verifyClientSession(hostname: String,
                                   session: SSLSession): Option[Throwable] =
    verifyPeerCertificates(session)

  override def verifyServerSession(hostname: String,
                                   session: SSLSession): Option[Throwable] =
    verifyPeerCertificates(session)

  private def verifyPeerCertificates(session: SSLSession) = {
    val mySubjectNames = getContext().subjectNames
    if (session.getPeerCertificates.length == 0) {
      Some(new IllegalArgumentException("No peer certificates"))
    }
    session.getPeerCertificates()(0) match {
      case x509: X509Certificate =>
        val peerSubjectNames = getAllSubjectNames(x509)
        if (mySubjectNames.exists(peerSubjectNames)) None
        else
          Some(
            new IllegalArgumentException(
              s"None of the peer subject names $peerSubjectNames were in local subject names $mySubjectNames"
            )
          )
      case other =>
        Some(
          new IllegalArgumentException(
            s"Unknown certificate type: ${other.getClass}"
          )
        )
    }

  }

  private def getAllSubjectNames(cert: X509Certificate) = {
    val maybeCommonName =
      new LdapName(cert.getSubjectX500Principal.getName).getRdns.asScala
        .collectFirst {
          case attr if attr.getType.equalsIgnoreCase("CN") =>
            attr.getValue.toString
        }

    val alternates = Option(cert.getSubjectAlternativeNames)
      .map(_.asScala)
      .getOrElse(Nil)
      .collect {
        // See the javadocs for what this list contains, first element should be an integer,
        // if that integer is 2, then the second element is a String containing the DNS name.
        case list if list.get(0) == 2 =>
          list.get(1) match {
            case dnsName: String => dnsName
            case other =>
              throw new IllegalArgumentException(
                s"Expected a string, but got a ${other.getClass}"
              )
          }
      }

    maybeCommonName.toSet ++ alternates
  }

}

object TlsMagicSSLEngineProvider {

  private case class CachedContext(cached: ConfiguredContext, expires: Deadline)

  private case class ConfiguredContext(context: SSLContext,
                                       subjectNames: Set[String])

}
