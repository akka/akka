package akka.remote.security.provider

import java.security.{ InvalidAlgorithmParameterException, KeyStore, KeyStoreException }
import javax.net.ssl.{ KeyManagerFactory, ManagerFactoryParameters, TrustManagerFactory }

import org.scalatest.{ Matchers, WordSpec }

class DelegatingCryptoSecurityProviderSpec extends WordSpec with Matchers {
  "DelegatingCryptoSecurityProviderSpec" which {
    "DelegatingKeyManagerFactory" should {
      "delegate" in {
        val keyManagerFactory =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm, DelegatingKeyManagerFactoryProvider)

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
        keyStore.load(null, null)
        val delegate = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
        delegate.init(keyStore, Array.emptyCharArray)

        keyManagerFactory.init(DelegatingKeyManagerFactoryParameters(delegate))

        keyManagerFactory.getKeyManagers shouldBe delegate.getKeyManagers
      }
      "throw if not initialised" in {
        val keyManagerFactory =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm, DelegatingKeyManagerFactoryProvider)

        intercept[IllegalStateException] {
          keyManagerFactory.getKeyManagers
        }
      }
      "throw if initialised with keystore" in {
        val keyManagerFactory =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm, DelegatingKeyManagerFactoryProvider)

        intercept[KeyStoreException] {
          keyManagerFactory.init(KeyStore.getInstance(KeyStore.getDefaultType), Array.emptyCharArray)
        }
      }
      "throw if initialised with ManagerFactoryParameters of wrong type" in {
        val keyManagerFactory =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm, DelegatingKeyManagerFactoryProvider)

        intercept[InvalidAlgorithmParameterException] {
          keyManagerFactory.init(new ManagerFactoryParameters {})
        }
      }
    }
    "DelegatingTrustManagerFactory" should {
      "delegate" in {
        val trustManagerFactory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm, DelegatingTrustManagerFactoryProvider)

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
        keyStore.load(null, null)
        val delegate = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
        delegate.init(keyStore)

        trustManagerFactory.init(DelegatingTrustManagerFactoryParameters(delegate))

        trustManagerFactory.getTrustManagers shouldBe delegate.getTrustManagers
      }
      "throw if not initialised" in {
        val trustManagerFactory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm, DelegatingTrustManagerFactoryProvider)

        intercept[IllegalStateException] {
          trustManagerFactory.getTrustManagers
        }
      }
      "throw if initialised with keystore" in {
        val trustManagerFactory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm, DelegatingTrustManagerFactoryProvider)

        intercept[KeyStoreException] {
          trustManagerFactory.init(KeyStore.getInstance(KeyStore.getDefaultType))
        }
      }
      "throw if initialised with ManagerFactoryParameters of wrong type" in {
        val trustManagerFactory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm, DelegatingTrustManagerFactoryProvider)

        intercept[InvalidAlgorithmParameterException] {
          trustManagerFactory.init(new ManagerFactoryParameters {})
        }
      }
    }
  }
}
