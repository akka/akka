/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp

import java.io.{ File, FileInputStream, FileNotFoundException, IOException }
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.security.cert.{ CertificateFactory, X509Certificate }
import java.security.{ GeneralSecurityException, KeyStore, PrivateKey, SecureRandom }
import java.util.concurrent.atomic.AtomicReference
import javax.naming.ldap.LdapName
import javax.net.ssl.{ KeyManagerFactory, SSLContext, SSLEngine, SSLSession, TrustManagerFactory }

import scala.concurrent.duration._

import com.typesafe.config.Config

import akka.actor.ActorSystem
import akka.pki.pem.{ DERPrivateKeyLoader, PEMDecoder }
import akka.stream.TLSRole
import akka.util.ccompat.JavaConverters._

final class RefreshingConfigSSLEngineProvider(val config: Config) extends SSLEngineProvider {
  import RefreshingConfigSSLEngineProvider._

  def this(system: ActorSystem) =
    this(system.settings.config.getConfig("akka.remote.artery.ssl.tls-magic-engine"))

  private val contextRef = new AtomicReference[Option[CachedContext]](None)

  private def getContext() = {
    contextRef.get() match {
      case Some(CachedContext(_, expired)) if expired.isOverdue() =>
        val context = constructContext()
        contextRef.set(Some(CachedContext(context, SSLContextCacheTime.fromNow)))
        context
      case Some(CachedContext(cached, _)) => cached
      case None =>
        val context = constructContext()
        contextRef.set(Some(CachedContext(context, SSLContextCacheTime.fromNow)))
        context
    }
  }

  private val rng = new SecureRandom()

  private val SSLKeyFile: String = config.getString("key-file")
  private val SSLCertFile: String = config.getString("cert-file")
  private val SSLCACertFile: String = config.getString("ca-cert-file")
  private val SSLContextCacheTime: FiniteDuration = config.getDuration("ssl-context-cache-ttl").toMillis.millis
  private val SSLEnabledAlgorithms: Set[String] = config.getStringList("enabled-algorithms").asScala.toSet
  private val SSLProtocol: String = config.getString("protocol")

  private def constructContext(): ConfiguredContext = {
    try {
      val certFactory = CertificateFactory.getInstance("X.509")
      val cert = certFactory.generateCertificate(new FileInputStream(SSLCertFile)).asInstanceOf[X509Certificate]
      val cacert = certFactory.generateCertificate(new FileInputStream(SSLCACertFile))

      val keyStore = KeyStore.getInstance("JKS")
      keyStore.load(null)
      // Load the private key
      val privateKey = loadPrivateKey(SSLKeyFile)

      keyStore.setCertificateEntry("cert", cert)
      keyStore.setCertificateEntry("cacert", cacert)
      keyStore.setKeyEntry("private-key", privateKey, "changeit".toCharArray, Array(cert, cacert))

      val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
      kmf.init(keyStore, "changeit".toCharArray)
      val keyManagers = kmf.getKeyManagers

      val trustStore = KeyStore.getInstance("JKS")
      trustStore.load(null)
      trustStore.setCertificateEntry("cacert", cacert)

      val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
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
          e)
      case e: IOException =>
        throw new SslTransportException("Server SSL connection could not be established because: " + e.getMessage, e)
      case e: GeneralSecurityException =>
        throw new SslTransportException(
          "Server SSL connection could not be established because SSL context could not be constructed",
          e)
      case e: IllegalArgumentException =>
        throw new SslTransportException("Server SSL connection could not be established because: " + e.getMessage, e)
    }
  }

  private def loadPrivateKey(filename: String): PrivateKey = {
    val bytes = Files.readAllBytes(new File(filename).toPath)
    val pemData = new String(bytes, StandardCharsets.UTF_8)
    DERPrivateKeyLoader.load(PEMDecoder.decode(pemData))
  }

  override def createServerSSLEngine(hostname: String, port: Int): SSLEngine =
    createSSLEngine(akka.stream.Server, hostname, port)

  override def createClientSSLEngine(hostname: String, port: Int): SSLEngine =
    createSSLEngine(akka.stream.Client, hostname, port)

  private def createSSLEngine(role: TLSRole, hostname: String, port: Int): SSLEngine = {
    createSSLEngine(getContext().context, role, hostname, port)
  }

  private def createSSLEngine(sslContext: SSLContext, role: TLSRole, hostname: String, port: Int): SSLEngine = {

    val engine = sslContext.createSSLEngine(hostname, port)

    engine.setUseClientMode(role == akka.stream.Client)
    engine.setEnabledCipherSuites(SSLEnabledAlgorithms.toArray)
    engine.setEnabledProtocols(Array(SSLProtocol))

    if (role != akka.stream.Client) engine.setNeedClientAuth(true)

    engine
  }

  override def verifyClientSession(hostname: String, session: SSLSession): Option[Throwable] =
    verifyPeerCertificates(session)

  override def verifyServerSession(hostname: String, session: SSLSession): Option[Throwable] =
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
              s"None of the peer subject names $peerSubjectNames were in local subject names $mySubjectNames"))
      case other =>
        Some(new IllegalArgumentException(s"Unknown certificate type: ${other.getClass}"))
    }

  }

  private def getAllSubjectNames(cert: X509Certificate) = {
    val maybeCommonName = new LdapName(cert.getSubjectX500Principal.getName).getRdns.asScala.collectFirst {
      case attr if attr.getType.equalsIgnoreCase("CN") =>
        attr.getValue.toString
    }

    val alternates = Option(cert.getSubjectAlternativeNames).map(_.asScala).getOrElse(Nil).collect {
      // See the javadocs for what this list contains, first element should be an integer,
      // if that integer is 2, then the second element is a String containing the DNS name.
      case list if list.get(0) == 2 =>
        list.get(1) match {
          case dnsName: String => dnsName
          case other           => throw new IllegalArgumentException(s"Expected a string, but got a ${other.getClass}")
        }
    }

    maybeCommonName.toSet ++ alternates
  }

}

object RefreshingConfigSSLEngineProvider {

  private case class CachedContext(cached: ConfiguredContext, expires: Deadline)

  private case class ConfiguredContext(context: SSLContext, subjectNames: Set[String])

}
