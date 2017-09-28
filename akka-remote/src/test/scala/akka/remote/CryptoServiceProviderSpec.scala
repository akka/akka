package akka.remote

import java.security.{ AccessController, KeyStore, PrivilegedAction, Provider }
import java.util.Collections
import javax.net.ssl._

import akka.actor.setup.ActorSystemSetup
import akka.event.NoMarkerLogging
import akka.remote.security.setup.{ CryptoServiceProviderSetup, KeyManagerFactorySetup, TrustManagerFactorySetup }
import akka.remote.transport.netty.SSLSettings
import com.typesafe.config.ConfigFactory
import org.scalatest.{ BeforeAndAfter, Inspectors, Matchers, WordSpec }

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

  private val dummyManagerFactoryParameters = new ManagerFactoryParameters {}

  "NettySSLHandler" when {
    "no CryptoServiceProviderSetup" should {
      "succeed" in {
        createSSLSettingsAndCreateContext(None)

        forAll(Constants.properties) {
          System.getProperty(_) shouldBe null
        }
      }
    }

    "KeyManagerFactorySetup included" should {
      "use given Provider without ManagerFactoryParameters" in {
        createSSLSettingsAndCreateContext(Some(KeyManagerFactorySetup(TestProvider, None)))

        System.getProperty(Constants.keyManagerFactoryInitWithKeystore) shouldBe Constants.accessed
        System.getProperty(Constants.keyManagerFactoryInitWithSpec) shouldBe null

        System.getProperty(Constants.trustManagerFactoryInitWithKeystore) shouldBe null
        System.getProperty(Constants.trustManagerFactoryInitWithSpec) shouldBe null
      }
      "use given Provider with ManagerFactoryParameters" in {
        createSSLSettingsAndCreateContext(Some(KeyManagerFactorySetup(TestProvider, Some(dummyManagerFactoryParameters))))

        System.getProperty(Constants.keyManagerFactoryInitWithKeystore) shouldBe null
        System.getProperty(Constants.keyManagerFactoryInitWithSpec) shouldBe Constants.accessed

        System.getProperty(Constants.trustManagerFactoryInitWithKeystore) shouldBe null
        System.getProperty(Constants.trustManagerFactoryInitWithSpec) shouldBe null
      }
    }
    "TrustManagerFactorySetup included" should {
      "use given Provider without ManagerFactoryParameters" in {
        createSSLSettingsAndCreateContext(Some(TrustManagerFactorySetup(TestProvider, None)))

        System.getProperty(Constants.keyManagerFactoryInitWithKeystore) shouldBe null
        System.getProperty(Constants.keyManagerFactoryInitWithSpec) shouldBe null

        System.getProperty(Constants.trustManagerFactoryInitWithKeystore) shouldBe Constants.accessed
        System.getProperty(Constants.trustManagerFactoryInitWithSpec) shouldBe null
      }
      "use given Provider with ManagerFactoryParameters" in {
        createSSLSettingsAndCreateContext(Some(TrustManagerFactorySetup(TestProvider, Some(dummyManagerFactoryParameters))))

        System.getProperty(Constants.keyManagerFactoryInitWithKeystore) shouldBe null
        System.getProperty(Constants.keyManagerFactoryInitWithSpec) shouldBe null

        System.getProperty(Constants.trustManagerFactoryInitWithKeystore) shouldBe null
        System.getProperty(Constants.trustManagerFactoryInitWithSpec) shouldBe Constants.accessed
      }
    }
  }

  private def createSSLSettingsAndCreateContext(setup: Option[CryptoServiceProviderSetup]) = {
    val sslSettings = new SSLSettings(
      knownFilesConfig,
      setup.map(ActorSystemSetup.empty.and).getOrElse(ActorSystemSetup.empty)
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