/*
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package com.typesafe.sslconfig.akka

import java.security.KeyStore
import java.security.cert.CertPathValidatorException
import java.util.Collections
import javax.net.ssl._

import akka.actor._
import akka.event.Logging
import com.typesafe.sslconfig.akka.util.AkkaLoggerFactory
import com.typesafe.sslconfig.ssl._
import com.typesafe.sslconfig.util.LoggerFactory

// TODO: remove again in 2.5.x, see https://github.com/akka/akka/issues/21753
object AkkaSSLConfig extends ExtensionId[AkkaSSLConfig] with ExtensionIdProvider {

  //////////////////// EXTENSION SETUP ///////////////////

  override def get(system: ActorSystem): AkkaSSLConfig = super.get(system)
  def apply()(implicit system: ActorSystem): AkkaSSLConfig = super.apply(system)

  override def lookup() = AkkaSSLConfig

  override def createExtension(system: ExtendedActorSystem): AkkaSSLConfig =
    new AkkaSSLConfig(system, defaultSSLConfigSettings(system))

  def defaultSSLConfigSettings(system: ActorSystem): SSLConfigSettings = {
    val akkaOverrides = system.settings.config.getConfig("akka.ssl-config")
    val defaults = system.settings.config.getConfig("ssl-config")
    SSLConfigFactory.parse(akkaOverrides withFallback defaults)
  }

}

final class AkkaSSLConfig(system: ExtendedActorSystem, val config: SSLConfigSettings) extends Extension {

  private val mkLogger = new AkkaLoggerFactory(system)

  private val log = Logging(system, getClass)
  log.debug("Initializing AkkaSSLConfig extension...")

  /** Can be used to modify the underlying config, most typically used to change a few values in the default config */
  def withSettings(c: SSLConfigSettings): AkkaSSLConfig =
    new AkkaSSLConfig(system, c)

  /**
   * Returns a new [[AkkaSSLConfig]] instance with the settings changed by the given function.
   * Please note that the ActorSystem-wide extension always remains configured via typesafe config,
   * custom ones can be created for special-handling specific connections
   */
  def mapSettings(f: SSLConfigSettings ⇒ SSLConfigSettings): AkkaSSLConfig =
    new AkkaSSLConfig(system, f(config))

  /**
   * Returns a new [[AkkaSSLConfig]] instance with the settings changed by the given function.
   * Please note that the ActorSystem-wide extension always remains configured via typesafe config,
   * custom ones can be created for special-handling specific connections
   *
   * Java API
   */
  // Not same signature as mapSettings to allow latter deprecation of this once we hit Scala 2.12
  def convertSettings(f: java.util.function.Function[SSLConfigSettings, SSLConfigSettings]): AkkaSSLConfig =
    new AkkaSSLConfig(system, f.apply(config))

  val hostnameVerifier = buildHostnameVerifier(config)

  val sslEngineConfigurator = {
    val sslContext = if (config.default) {
      log.info("ssl-config.default is true, using the JDK's default SSLContext")
      validateDefaultTrustManager(config)
      SSLContext.getDefault
    } else {
      // break out the static methods as much as we can...
      val keyManagerFactory = buildKeyManagerFactory(config)
      val trustManagerFactory = buildTrustManagerFactory(config)
      new ConfigSSLContextBuilder(mkLogger, config, keyManagerFactory, trustManagerFactory).build()
    }

    // protocols!
    val defaultParams = sslContext.getDefaultSSLParameters
    val defaultProtocols = defaultParams.getProtocols
    val protocols = configureProtocols(defaultProtocols, config)

    // ciphers!
    val defaultCiphers = defaultParams.getCipherSuites
    val cipherSuites = configureCipherSuites(defaultCiphers, config)

    // apply "loose" settings
    // !! SNI!
    looseDisableSNI(defaultParams)

    new DefaultSSLEngineConfigurator(config, protocols, cipherSuites)
  }

  ////////////////// CONFIGURING //////////////////////

  def buildKeyManagerFactory(ssl: SSLConfigSettings): KeyManagerFactoryWrapper = {
    val keyManagerAlgorithm = ssl.keyManagerConfig.algorithm
    new DefaultKeyManagerFactoryWrapper(keyManagerAlgorithm)
  }

  def buildTrustManagerFactory(ssl: SSLConfigSettings): TrustManagerFactoryWrapper = {
    val trustManagerAlgorithm = ssl.trustManagerConfig.algorithm
    new DefaultTrustManagerFactoryWrapper(trustManagerAlgorithm)
  }

  def buildHostnameVerifier(conf: SSLConfigSettings): HostnameVerifier = {
    val clazz: Class[HostnameVerifier] =
      if (config.loose.disableHostnameVerification) classOf[DisabledComplainingHostnameVerifier].asInstanceOf[Class[HostnameVerifier]]
      else config.hostnameVerifierClass.asInstanceOf[Class[HostnameVerifier]]

    val v = system.dynamicAccess.createInstanceFor[HostnameVerifier](clazz, Nil)
      .orElse(system.dynamicAccess.createInstanceFor[HostnameVerifier](clazz, List(classOf[LoggerFactory] → mkLogger)))
      .getOrElse(throw new Exception("Unable to obtain hostname verifier for class: " + clazz))

    log.debug("buildHostnameVerifier: created hostname verifier: {}", v)
    v
  }

  def validateDefaultTrustManager(sslConfig: SSLConfigSettings) {
    // If we are using a default SSL context, we can't filter out certificates with weak algorithms
    // We ALSO don't have access to the trust manager from the SSLContext without doing horrible things
    // with reflection.
    //
    // However, given that the default SSLContextImpl will call out to the TrustManagerFactory and any
    // configuration with system properties will also apply with the factory, we can use the factory
    // method to recreate the trust manager and validate the trust certificates that way.
    //
    // This is really a last ditch attempt to satisfy https://wiki.mozilla.org/CA:MD5and1024 on root certificates.
    //
    // http://grepcode.com/file/repository.grepcode.com/java/root/jdk/openjdk/7-b147/sun/security/ssl/SSLContextImpl.java#79

    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(null.asInstanceOf[KeyStore])
    val trustManager: X509TrustManager = tmf.getTrustManagers()(0).asInstanceOf[X509TrustManager]

    //    val disabledKeyAlgorithms = sslConfig.disabledKeyAlgorithms.getOrElse(Algorithms.disabledKeyAlgorithms) // was Option
    val disabledKeyAlgorithms = sslConfig.disabledKeyAlgorithms.mkString(",") // TODO Sub optimal, we got a Seq...
    val constraints = AlgorithmConstraintsParser.parseAll(AlgorithmConstraintsParser.line, disabledKeyAlgorithms).get.toSet
    val algorithmChecker = new AlgorithmChecker(mkLogger, keyConstraints = constraints, signatureConstraints = Set())
    for (cert ← trustManager.getAcceptedIssuers) {
      try {
        algorithmChecker.checkKeyAlgorithms(cert)
      } catch {
        case e: CertPathValidatorException ⇒
          log.warning("You are using ssl-config.default=true and have a weak certificate in your default trust store! (You can modify akka.ssl-config.disabledKeyAlgorithms to remove this message.)", e)
      }
    }
  }

  def configureProtocols(existingProtocols: Array[String], sslConfig: SSLConfigSettings): Array[String] = {
    val definedProtocols = sslConfig.enabledProtocols match {
      case Some(configuredProtocols) ⇒
        // If we are given a specific list of protocols, then return it in exactly that order,
        // assuming that it's actually possible in the SSL context.
        configuredProtocols.filter(existingProtocols.contains).toArray

      case None ⇒
        // Otherwise, we return the default protocols in the given list.
        Protocols.recommendedProtocols.filter(existingProtocols.contains)
    }

    val allowWeakProtocols = sslConfig.loose.allowWeakProtocols
    if (!allowWeakProtocols) {
      val deprecatedProtocols = Protocols.deprecatedProtocols
      for (deprecatedProtocol ← deprecatedProtocols) {
        if (definedProtocols.contains(deprecatedProtocol)) {
          throw new IllegalStateException(s"Weak protocol $deprecatedProtocol found in ssl-config.protocols!")
        }
      }
    }
    definedProtocols
  }

  def configureCipherSuites(existingCiphers: Array[String], sslConfig: SSLConfigSettings): Array[String] = {
    val definedCiphers = sslConfig.enabledCipherSuites match {
      case Some(configuredCiphers) ⇒
        // If we are given a specific list of ciphers, return it in that order.
        configuredCiphers.filter(existingCiphers.contains(_)).toArray

      case None ⇒
        Ciphers.recommendedCiphers.filter(existingCiphers.contains(_)).toArray
    }

    val allowWeakCiphers = sslConfig.loose.allowWeakCiphers
    if (!allowWeakCiphers) {
      val deprecatedCiphers = Ciphers.deprecatedCiphers
      for (deprecatedCipher ← deprecatedCiphers) {
        if (definedCiphers.contains(deprecatedCipher)) {
          throw new IllegalStateException(s"Weak cipher $deprecatedCipher found in ssl-config.ciphers!")
        }
      }
    }
    definedCiphers
  }

  // LOOSE SETTINGS //

  private def looseDisableSNI(defaultParams: SSLParameters): Unit = if (config.loose.disableSNI) {
    // this will be logged once for each AkkaSSLConfig
    log.warning("You are using ssl-config.loose.disableSNI=true! " +
      "It is strongly discouraged to disable Server Name Indication, as it is crucial to preventing man-in-the-middle attacks.")

    defaultParams.setServerNames(Collections.emptyList())
    defaultParams.setSNIMatchers(Collections.emptyList())
  }

}
