package akka.remote

import java.security.{AccessController, KeyStore, PrivilegedAction, Provider}
import java.util.Collections
import javax.net.ssl._

import akka.actor.setup.ActorSystemSetup
import akka.event.NoMarkerLogging
import akka.remote.security.setup.{CryptoServiceProviderSetup, KeyManagerFactoryParameters, TrustManagerFactoryParameters}
import akka.remote.transport.netty.SSLSettings
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfter, Inspectors, Matchers, WordSpec}

class CryptoServiceProviderSpec extends WordSpec with Matchers with BeforeAndAfter with Inspectors {

  private val trustStore = getClass.getClassLoader.getResource("truststore").getPath
  private val keyStore = getClass.getClassLoader.getResource("keystore").getPath

  before {
    Constants.properties.foreach(System.clearProperty)
  }

  private val fileConfigString = {
    ConfigFactory.parseString(
      s"""
      |  trust-store = $trustStore
      |  key-store   = $keyStore""".stripMargin)
  }

  private val referenceConfig = ConfigFactory.defaultReference().getConfig("akka.remote.netty.ssl.security")

  private val knownFilesConfig = fileConfigString.withFallback(referenceConfig)

  private val dummyManagerFactoryParameters = new ManagerFactoryParameters with KeyManagerFactoryParameters with TrustManagerFactoryParameters {}

  "NettySSLHandler" when {
    "no CryptoServiceProviderSetup" should {
      "succeed" in {
        val sslSettings = new SSLSettings(knownFilesConfig, ActorSystemSetup.empty)

        sslSettings.cryptoServiceProviderSetup shouldBe None

        sslSettings.getOrCreateContext(NoMarkerLogging)

        forAll(Constants.properties) {
          System.getProperty(_) shouldBe null
        }
      }
    }
    "CryptoServiceProviderSetup included" should {
      def createSSLSettingsAndCreateContext(setup: CryptoServiceProviderSetup) = {
        val sslSettings = new SSLSettings(
          knownFilesConfig,
          ActorSystemSetup.empty.and(setup)
        )
        sslSettings.getOrCreateContext(NoMarkerLogging)

        sslSettings.cryptoServiceProviderSetup shouldBe Some(setup)
      }

      "use given Provider without ManagerFactoryParameters" in {
        createSSLSettingsAndCreateContext(CryptoServiceProviderSetup(TestProvider, None, None))

        System.getProperty(Constants.keyManagerFactoryInitWithKeystore) shouldBe Constants.accessed
        System.getProperty(Constants.keyManagerFactoryInitWithSpec) shouldBe null

        System.getProperty(Constants.trustManagerFactoryInitWithKeystore) shouldBe Constants.accessed
        System.getProperty(Constants.trustManagerFactoryInitWithSpec) shouldBe null
      }
      "use given Provider with ManagerFactoryParameters for KeyManagerFactory" in {
        createSSLSettingsAndCreateContext(CryptoServiceProviderSetup(TestProvider, Some(dummyManagerFactoryParameters), None))

        System.getProperty(Constants.keyManagerFactoryInitWithKeystore) shouldBe null
        System.getProperty(Constants.keyManagerFactoryInitWithSpec) shouldBe Constants.accessed

        System.getProperty(Constants.trustManagerFactoryInitWithKeystore) shouldBe Constants.accessed
        System.getProperty(Constants.trustManagerFactoryInitWithSpec) shouldBe null
      }
      "use given Provider with ManagerFactoryParameters for TrustManagerFactory" in {
        createSSLSettingsAndCreateContext(CryptoServiceProviderSetup(TestProvider, None, Some(dummyManagerFactoryParameters)))

        System.getProperty(Constants.keyManagerFactoryInitWithKeystore) shouldBe Constants.accessed
        System.getProperty(Constants.keyManagerFactoryInitWithSpec) shouldBe null

        System.getProperty(Constants.trustManagerFactoryInitWithKeystore) shouldBe null
        System.getProperty(Constants.trustManagerFactoryInitWithSpec) shouldBe Constants.accessed
      }
    }
  }
}

object TestProvider extends Provider("test-provider", 1.0d, "test provider") { outer ⇒
  AccessController.doPrivileged(new PrivilegedAction[outer.type] {
    override def run() = {
      putService(new Provider.Service(outer, "KeyManagerFactory", KeyManagerFactory.getDefaultAlgorithm, classOf[SpyKeyManagerFactorySpi].getCanonicalName, Collections.emptyList(), Collections.emptyMap()))
      putService(new Provider.Service(outer, "TrustManagerFactory", TrustManagerFactory.getDefaultAlgorithm, classOf[SpyTrustManagerFactorySpi].getCanonicalName, Collections.emptyList(), Collections.emptyMap()))
      outer
    }
  })
}

object Constants {
  private val namespace = "test.spy."
  val keyManagerFactoryInitWithSpec = namespace + "key-manager-factory-init-spec"
  val keyManagerFactoryInitWithKeystore = namespace + "key-manager-factory-init-keystore"
  val trustManagerFactoryInitWithSpec = namespace + "trust-manager-factory-init-spec"
  val trustManagerFactoryInitWithKeystore = namespace + "trust-manager-factory-init-keystore"

  val properties = Seq(
    keyManagerFactoryInitWithSpec,
    keyManagerFactoryInitWithKeystore,
    trustManagerFactoryInitWithSpec,
    trustManagerFactoryInitWithKeystore
  )

  val accessed = "accessed"
}

class SpyKeyManagerFactorySpi extends KeyManagerFactorySpi {
  override def engineGetKeyManagers(): Array[KeyManager] = Array.empty

  override def engineInit(ks: KeyStore, password: Array[Char]): Unit =
    System.setProperty(Constants.keyManagerFactoryInitWithKeystore, Constants.accessed)

  override def engineInit(spec: ManagerFactoryParameters): Unit =
    System.setProperty(Constants.keyManagerFactoryInitWithSpec, Constants.accessed)
}

class SpyTrustManagerFactorySpi extends TrustManagerFactorySpi {
  override def engineGetTrustManagers(): Array[TrustManager] = Array.empty

  override def engineInit(ks: KeyStore): Unit =
    System.setProperty(Constants.trustManagerFactoryInitWithKeystore, Constants.accessed)

  override def engineInit(spec: ManagerFactoryParameters): Unit =
    System.setProperty(Constants.trustManagerFactoryInitWithSpec, Constants.accessed)
}