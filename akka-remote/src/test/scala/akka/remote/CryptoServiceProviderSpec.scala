/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import java.security.{ AccessController, KeyStore, PrivilegedAction, Provider }
import java.util.Collections
import javax.net.ssl._

import akka.actor.setup.{ ActorSystemSetup, Setup }
import akka.event.NoMarkerLogging
import akka.remote.security.setup.{ KeyManagerFactorySetup, TrustManagerFactorySetup }
import akka.remote.transport.netty.SSLSettings
import com.typesafe.config.ConfigFactory
import org.scalatest.{ BeforeAndAfter, Inspectors, Matchers, WordSpec }

class CryptoServiceProviderSpec extends WordSpec with Matchers with BeforeAndAfter with Inspectors {

  private val trustStore = getClass.getClassLoader.getResource("truststore").getPath
  private val keyStore = getClass.getClassLoader.getResource("keystore").getPath

  before {
    clearProperties()
  }

  after {
    clearProperties()
  }

  private def clearProperties() = {
    Constants.Properties.foreach(System.clearProperty)
  }

  private val fileConfigString = {
    ConfigFactory.parseString(
      s"""
      |  trust-store = $trustStore
      |  key-store   = $keyStore""".stripMargin)
  }

  private val referenceConfig = ConfigFactory.defaultReference().getConfig("akka.remote.netty.ssl.security")

  private val knownFilesConfig = fileConfigString.withFallback(referenceConfig)

  private val dummyManagerFactoryParameters = new ManagerFactoryParameters {}

  "NettySSLHandler" when {
    "no CryptoServiceProviderSetup" should {
      "succeed" in {
        createSSLSettingsAndCreateContext(None)

        forAll(Constants.Properties) {
          System.getProperty(_) shouldBe null
        }
      }
    }

    "KeyManagerFactorySetup included" should {
      "use given Provider without ManagerFactoryParameters" in {
        createSSLSettingsAndCreateContext(Some(KeyManagerFactorySetup(TestProvider, None)))

        System.getProperty(Constants.KeyManagerFactoryInitWithKeystore) shouldBe Constants.Accessed
        System.getProperty(Constants.KeyManagerFactoryInitWithSpec) shouldBe null

        System.getProperty(Constants.TrustManagerFactoryInitWithKeystore) shouldBe null
        System.getProperty(Constants.TrustManagerFactoryInitWithSpec) shouldBe null
      }
      "use given Provider with ManagerFactoryParameters" in {
        createSSLSettingsAndCreateContext(Some(KeyManagerFactorySetup(TestProvider, Some(dummyManagerFactoryParameters))))

        System.getProperty(Constants.KeyManagerFactoryInitWithKeystore) shouldBe null
        System.getProperty(Constants.KeyManagerFactoryInitWithSpec) shouldBe Constants.Accessed

        System.getProperty(Constants.TrustManagerFactoryInitWithKeystore) shouldBe null
        System.getProperty(Constants.TrustManagerFactoryInitWithSpec) shouldBe null
      }
    }
    "TrustManagerFactorySetup included" should {
      "use given Provider without ManagerFactoryParameters" in {
        createSSLSettingsAndCreateContext(Some(TrustManagerFactorySetup(TestProvider, None)))

        System.getProperty(Constants.KeyManagerFactoryInitWithKeystore) shouldBe null
        System.getProperty(Constants.KeyManagerFactoryInitWithSpec) shouldBe null

        System.getProperty(Constants.TrustManagerFactoryInitWithKeystore) shouldBe Constants.Accessed
        System.getProperty(Constants.TrustManagerFactoryInitWithSpec) shouldBe null
      }
      "use given Provider with ManagerFactoryParameters" in {
        createSSLSettingsAndCreateContext(Some(TrustManagerFactorySetup(TestProvider, Some(dummyManagerFactoryParameters))))

        System.getProperty(Constants.KeyManagerFactoryInitWithKeystore) shouldBe null
        System.getProperty(Constants.KeyManagerFactoryInitWithSpec) shouldBe null

        System.getProperty(Constants.TrustManagerFactoryInitWithKeystore) shouldBe null
        System.getProperty(Constants.TrustManagerFactoryInitWithSpec) shouldBe Constants.Accessed
      }
    }
  }

  private def createSSLSettingsAndCreateContext(setup: Option[Setup]) = {
    val sslSettings = new SSLSettings(
      knownFilesConfig,
      ActorSystemSetup(setup.toArray: _*)
    )
    sslSettings.getOrCreateContext(NoMarkerLogging)

    setup match {
      case Some(_: KeyManagerFactorySetup)   ⇒ sslSettings.keyManagerFactorySetup shouldBe setup
      case Some(_: TrustManagerFactorySetup) ⇒ sslSettings.trustManagerFactorySetup shouldBe setup
      case Some(unexpected)                  ⇒ fail(s"Unexpected setup type $unexpected")
      case None                              ⇒
    }
  }
}

object TestProvider extends Provider("test-provider", 1.0d, "test provider") { outer ⇒
  AccessController.doPrivileged(new PrivilegedAction[Unit] {
    override def run() = {
      putService(new Provider.Service(outer, "KeyManagerFactory", KeyManagerFactory.getDefaultAlgorithm, classOf[SpyKeyManagerFactorySpi].getCanonicalName, Collections.emptyList(), Collections.emptyMap()))
      putService(new Provider.Service(outer, "TrustManagerFactory", TrustManagerFactory.getDefaultAlgorithm, classOf[SpyTrustManagerFactorySpi].getCanonicalName, Collections.emptyList(), Collections.emptyMap()))
    }
  })
}

object Constants {
  private val Namespace = "test.spy."
  val KeyManagerFactoryInitWithSpec = Namespace + "key-manager-factory-init-spec"
  val KeyManagerFactoryInitWithKeystore = Namespace + "key-manager-factory-init-keystore"
  val TrustManagerFactoryInitWithSpec = Namespace + "trust-manager-factory-init-spec"
  val TrustManagerFactoryInitWithKeystore = Namespace + "trust-manager-factory-init-keystore"

  val Properties = Seq(
    KeyManagerFactoryInitWithSpec,
    KeyManagerFactoryInitWithKeystore,
    TrustManagerFactoryInitWithSpec,
    TrustManagerFactoryInitWithKeystore
  )

  val Accessed = "accessed"
}

class SpyKeyManagerFactorySpi extends KeyManagerFactorySpi {
  override def engineGetKeyManagers(): Array[KeyManager] = Array.empty

  override def engineInit(ks: KeyStore, password: Array[Char]): Unit =
    System.setProperty(Constants.KeyManagerFactoryInitWithKeystore, Constants.Accessed)

  override def engineInit(spec: ManagerFactoryParameters): Unit =
    System.setProperty(Constants.KeyManagerFactoryInitWithSpec, Constants.Accessed)
}

class SpyTrustManagerFactorySpi extends TrustManagerFactorySpi {
  override def engineGetTrustManagers(): Array[TrustManager] = Array.empty

  override def engineInit(ks: KeyStore): Unit =
    System.setProperty(Constants.TrustManagerFactoryInitWithKeystore, Constants.Accessed)

  override def engineInit(spec: ManagerFactoryParameters): Unit =
    System.setProperty(Constants.TrustManagerFactoryInitWithSpec, Constants.Accessed)
}
