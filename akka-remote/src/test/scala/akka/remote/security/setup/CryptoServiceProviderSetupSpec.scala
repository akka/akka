/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.security.setup

import java.security.{ AccessController, KeyStore, PrivilegedAction, Provider }
import java.util.Collections.{ emptyList, emptyMap }
import javax.net.ssl._

import akka.remote.security.setup.DummyKeyManagerFactorySpi.MemberDummyKeyManagerFactorySpi
import akka.remote.security.setup.DummyTrustManagerFactorySpi.MemberDummyTrustManagerFactorySpi
import org.scalatest.{ Matchers, WordSpec }

class CryptoServiceProviderSetupSpec extends WordSpec with Matchers {
  "KeyManagerFactorySetup" when {
    "creating a factorySetup for a provider for the given spi class" should {
      "create provider with correct name" in {
        val setup: KeyManagerFactorySetup = KeyManagerFactorySetup.providing[DummyKeyManagerFactorySpi]

        setup.provider.getName should include(classOf[DummyKeyManagerFactorySpi].getSimpleName)
      }

      "create provider using the given spi class" in {
        val setup: KeyManagerFactorySetup = KeyManagerFactorySetup.providing[DummyKeyManagerFactorySpi]

        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm, setup.provider).getKeyManagers shouldBe CryptoServiceProviderSetupJavaAPITest.dummyKeyManagers
      }

      "have None parameters if None given" in {
        val setup: KeyManagerFactorySetup = KeyManagerFactorySetup.providing[DummyKeyManagerFactorySpi]

        setup.keyManagerFactoryParameters shouldBe None
      }

      "have Some parameters if Some given" in {
        val setup: KeyManagerFactorySetup = KeyManagerFactorySetup.providing[DummyKeyManagerFactorySpi](CryptoServiceProviderSetupJavaAPITest.dummyManagerFactoryParameters)

        setup.keyManagerFactoryParameters shouldBe Some(CryptoServiceProviderSetupJavaAPITest.dummyManagerFactoryParameters)
      }

      "throw meaningful exception if attempting to use a local spi class" in {
        class LocalDummyKeyManagerFactorySpi extends KeyManagerFactorySpi {
          override def engineGetKeyManagers(): Array[KeyManager] = ???

          override def engineInit(ks: KeyStore, password: Array[Char]): Unit = ???

          override def engineInit(spec: ManagerFactoryParameters): Unit = ???
        }

        val exception = intercept[IllegalArgumentException] {
          KeyManagerFactorySetup.providing[LocalDummyKeyManagerFactorySpi]
        }

        exception.getMessage should include("local")
      }

      "throw meaningful exception if attempting to use a non public spi class" in {
        val exception = intercept[IllegalArgumentException] {
          KeyManagerFactorySetup.providing[NonPublicDummyKeyManagerFactorySpi]
        }

        exception.getMessage should include("public")
      }

      "throw meaningful exception if attempting to use a member spi class" in {
        val exception = intercept[IllegalArgumentException] {
          KeyManagerFactorySetup.providing[MemberDummyKeyManagerFactorySpi]
        }

        exception.getMessage should include("member")
      }
    }
    "creating a factorySetup for a provider that delegates to the given KeyManagerFactory" should {
      "provide a Provider and ManagerFactoryParameters that can be used to instantiate a delegating KeyManagerFactory" in {
        val delegateKeyManagerFactory = {
          val provider = new Provider("kmf-dummy-provider", 1.0d, "KMF dummy provider") { outer ⇒
            AccessController.doPrivileged(new PrivilegedAction[Unit] {
              override def run(): Unit = {
                putService(new Provider.Service(
                  outer, "KeyManagerFactory", KeyManagerFactory.getDefaultAlgorithm, classOf[DummyKeyManagerFactorySpi].getCanonicalName, emptyList(), emptyMap()
                ))
              }
            })
          }
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm, provider)
        }

        val setup = KeyManagerFactorySetup.delegatingTo(delegateKeyManagerFactory)

        setup match {
          case KeyManagerFactorySetup(provider, Some(parameters)) ⇒
            val newKeyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm, provider)
            newKeyManagerFactory.init(parameters)
            newKeyManagerFactory.getKeyManagers should (not be empty and be(delegateKeyManagerFactory.getKeyManagers))
          case _ ⇒
            fail("Expected a KeyManagerFactorySetup with a Provider and a ManagerFactoryParameters")
        }
      }
    }
  }

  "TrustManagerFactorySetup" when {
    "creating a factorySetup for a provider for the given spi class" should {
      "create provider with correct name" in {
        val setup: TrustManagerFactorySetup = TrustManagerFactorySetup.providing[DummyTrustManagerFactorySpi]

        setup.provider.getName should include(classOf[DummyTrustManagerFactorySpi].getSimpleName)
      }

      "create provider using the given spi class" in {
        val setup: TrustManagerFactorySetup = TrustManagerFactorySetup.providing[DummyTrustManagerFactorySpi]

        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm, setup.provider).getTrustManagers shouldBe CryptoServiceProviderSetupJavaAPITest.dummyTrustManagers
      }

      "have None parameters if None given" in {
        val setup: TrustManagerFactorySetup = TrustManagerFactorySetup.providing[DummyTrustManagerFactorySpi]

        setup.trustManagerFactoryParameters shouldBe None
      }

      "have Some parameters if Some given" in {
        val setup: TrustManagerFactorySetup = TrustManagerFactorySetup.providing[DummyTrustManagerFactorySpi](CryptoServiceProviderSetupJavaAPITest.dummyManagerFactoryParameters)

        setup.trustManagerFactoryParameters shouldBe Some(CryptoServiceProviderSetupJavaAPITest.dummyManagerFactoryParameters)
      }

      "throw meaningful exception if attempting to use a local spi class" in {
        class LocalDummyTrustManagerFactorySpi extends TrustManagerFactorySpi {
          override def engineGetTrustManagers(): Array[TrustManager] = ???

          override def engineInit(ks: KeyStore): Unit = ???

          override def engineInit(spec: ManagerFactoryParameters): Unit = ???
        }

        val exception = intercept[IllegalArgumentException] {
          TrustManagerFactorySetup.providing[LocalDummyTrustManagerFactorySpi]
        }

        exception.getMessage should include("local")
      }

      "throw meaningful exception if attempting to use a non public spi class" in {
        val exception = intercept[IllegalArgumentException] {
          TrustManagerFactorySetup.providing[NonPublicDummyTrustManagerFactorySpi]
        }

        exception.getMessage should include("public")
      }

      "throw meaningful exception if attempting to use a member spi class" in {
        val exception = intercept[IllegalArgumentException] {
          TrustManagerFactorySetup.providing[MemberDummyTrustManagerFactorySpi]
        }

        exception.getMessage should include("member")
      }
    }
    "creating a factorySetup for a provider that delegates to the given KeyManagerFactory" should {
      "provide a Provider and ManagerFactoryParameters that can be used to instantiate a delegating TrustManagerFactory" in {
        val delegateTrustManagerFactory = {
          val provider = new Provider("kmf-dummy-provider", 1.0d, "KMF dummy provider") {
            outer ⇒
            AccessController.doPrivileged(new PrivilegedAction[Unit] {
              override def run(): Unit = {
                putService(new Provider.Service(
                  outer, "TrustManagerFactory", TrustManagerFactory.getDefaultAlgorithm, classOf[DummyTrustManagerFactorySpi].getCanonicalName, emptyList(), emptyMap()
                ))
              }
            })
          }
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm, provider)
        }

        val setup = TrustManagerFactorySetup.delegatingTo(delegateTrustManagerFactory)

        setup match {
          case TrustManagerFactorySetup(provider, Some(parameters)) ⇒
            val newTrustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm, provider)
            newTrustManagerFactory.init(parameters)
            newTrustManagerFactory.getTrustManagers should (not be empty and be(delegateTrustManagerFactory.getTrustManagers))
          case _ ⇒
            fail("Expected a TrustManagerFactorySetup with a Provider and a ManagerFactoryParameters")
        }
      }
    }
  }
}
