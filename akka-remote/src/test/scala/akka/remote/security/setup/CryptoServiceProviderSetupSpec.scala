package akka.remote.security.setup

import java.security.KeyStore
import javax.net.ssl._

import akka.remote.security.setup.DummyKeyManagerFactorySpi.MemberDummyKeyManagerFactorySpi
import akka.remote.security.setup.DummyTrustManagerFactorySpi.MemberDummyTrustManagerFactorySpi
import org.scalatest.{ Matchers, WordSpec }

import scala.reflect.ClassTag

class CryptoServiceProviderSetupSpec extends WordSpec with Matchers {
  "KeyManagerFactorySetup" when {
    "creating a factorySetup for a provider for the given spi class" should {
      "create provider with correct name" in {
        val setup: KeyManagerFactorySetup = KeyManagerFactorySetup.providing[DummyKeyManagerFactorySpi]()

        setup.provider.getName should include(classOf[DummyKeyManagerFactorySpi].getSimpleName)
      }

      "create provider using the given spi class" in {
        val setup: KeyManagerFactorySetup = KeyManagerFactorySetup.providing[DummyKeyManagerFactorySpi]()

        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm, setup.provider).getKeyManagers shouldBe CryptoServiceProviderSetupJavaAPITest.dummyKeyManagers
      }

      "have None parameters if None given" in {
        val setup: KeyManagerFactorySetup = KeyManagerFactorySetup.providing[DummyKeyManagerFactorySpi]()

        setup.keyManagerFactoryParameters shouldBe None
      }

      "have Some parameters if Some given" in {
        val setup: KeyManagerFactorySetup = KeyManagerFactorySetup.providing[DummyKeyManagerFactorySpi](Some(CryptoServiceProviderSetupJavaAPITest.dummyManagerFactoryParameters))

        setup.keyManagerFactoryParameters shouldBe Some(CryptoServiceProviderSetupJavaAPITest.dummyManagerFactoryParameters)
      }

      "throw meaningful exception if attempting to use a local spi class" in {
        class LocalDummyKeyManagerFactorySpi extends KeyManagerFactorySpi {
          override def engineGetKeyManagers(): Array[KeyManager] = ???

          override def engineInit(ks: KeyStore, password: Array[Char]): Unit = ???

          override def engineInit(spec: ManagerFactoryParameters): Unit = ???
        }

        val exception = intercept[IllegalArgumentException] {
          KeyManagerFactorySetup.providing[LocalDummyKeyManagerFactorySpi]()
        }

        exception.getMessage should include("local")
      }

      "throw meaningful exception if attempting to use a non public spi class" in {
        val exception = intercept[IllegalArgumentException] {
          KeyManagerFactorySetup.providing[NonPublicDummyKeyManagerFactorySpi]()
        }

        exception.getMessage should include("public")
      }

      "throw meaningful exception if attempting to use a member spi class" in {
        val exception = intercept[IllegalArgumentException] {
          KeyManagerFactorySetup.providing[MemberDummyKeyManagerFactorySpi]()
        }

        exception.getMessage should include("member")
      }
    }
  }

  "TrustManagerFactorySetup" when {
    "creating a factorySetup for a provider for the given spi class" should {
      "create provider with correct name" in {
        val setup: TrustManagerFactorySetup = TrustManagerFactorySetup.providing[DummyTrustManagerFactorySpi]()

        setup.provider.getName should include(classOf[DummyTrustManagerFactorySpi].getSimpleName)
      }

      "create provider using the given spi class" in {
        val setup: TrustManagerFactorySetup = TrustManagerFactorySetup.providing[DummyTrustManagerFactorySpi]()

        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm, setup.provider).getTrustManagers shouldBe CryptoServiceProviderSetupJavaAPITest.dummyTrustManagers
      }

      "have None parameters if None given" in {
        val setup: TrustManagerFactorySetup = TrustManagerFactorySetup.providing[DummyTrustManagerFactorySpi]()

        setup.trustManagerFactoryParameters shouldBe None
      }

      "have Some parameters if Some given" in {
        val setup: TrustManagerFactorySetup = TrustManagerFactorySetup.providing[DummyTrustManagerFactorySpi](Some(CryptoServiceProviderSetupJavaAPITest.dummyManagerFactoryParameters))

        setup.trustManagerFactoryParameters shouldBe Some(CryptoServiceProviderSetupJavaAPITest.dummyManagerFactoryParameters)
      }

      "throw meaningful exception if attempting to use a local spi class" in {
        class LocalDummyTrustManagerFactorySpi extends TrustManagerFactorySpi {
          override def engineGetTrustManagers(): Array[TrustManager] = ???

          override def engineInit(ks: KeyStore): Unit = ???

          override def engineInit(spec: ManagerFactoryParameters): Unit = ???
        }

        val exception = intercept[IllegalArgumentException] {
          TrustManagerFactorySetup.providing[LocalDummyTrustManagerFactorySpi]()
        }

        exception.getMessage should include("local")
      }

      "throw meaningful exception if attempting to use a non public spi class" in {
        val exception = intercept[IllegalArgumentException] {
          TrustManagerFactorySetup.providing[NonPublicDummyTrustManagerFactorySpi]()
        }

        exception.getMessage should include("public")
      }

      "throw meaningful exception if attempting to use a member spi class" in {
        val exception = intercept[IllegalArgumentException] {
          TrustManagerFactorySetup.providing[MemberDummyTrustManagerFactorySpi]()
        }

        exception.getMessage should include("member")
      }
    }
  }
}
