/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp.ssl

import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger

import akka.event.MarkerLoggingAdapter
import akka.event.NoMarkerLogging
import akka.remote.artery.tcp.SecureRandomFactory
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Tests only the caching on an [[SSLFactory]]. All other settings are tested
 * using integration tests that exercise the SSLContext built inside the factory.
 */
class SslFactoryCachingSpec extends AnyWordSpec with Matchers with Eventually {

  val cachingInMillis = 100

  "SslFactory's caching" must {

    "provide an SSLContext" in {
      val factoryConfig = quickConfig()
      val context = new SslFactory(factoryConfig, managerProvider, prng, sessionVerifierFactory)(logger).sslContext
      context mustNot be(null)
    }

    "provide a new SSLContext once the cache has expired" in {
      val factory = new SslFactory(quickConfig(), managerProvider, prng, sessionVerifierFactory)(logger)
      val c1 = factory.sslContext
      eventually {
        factory.sslContext mustNot be(c1)
      }
    }

    "provide a new SessionVerifier once the cache has expired" in {
      val svFactory: SslManagersProvider => SessionVerifier = _ =>
        new SessionVerifier {
          override def verifyClientSession(hostname: String, session: SSLSession): Option[Throwable] = None

          override def verifyServerSession(hostname: String, session: SSLSession): Option[Throwable] = None
        }

      val factory = new SslFactory(quickConfig(), managerProvider, prng, svFactory)(logger)
      val sv1 = factory.sessionVerifier
      eventually {
        factory.sessionVerifier mustNot be(sv1)
      }
    }

    "use the managerProvider to build a new SSLContext (e.g., reading the files again)" in {
      val inMemProvider = new InMemSslManagersProvider
      val factory = new SslFactory(quickConfig(), _ => inMemProvider, prng, sessionVerifierFactory)(logger)

      val c1 = factory.sslContext
      eventually {
        factory.sslContext mustNot be(c1)
      }

      // In this test the methods in the SslManagersProvider are only invoked twice
      // because the sessionVerifierFactory is a Noop. First invocation is to build
      // c1 and the second one happens when `factory.sslContext is not c1`
      inMemProvider.keyInvocations.intValue() must be(2)
      inMemProvider.trustInvocations.intValue() must be(2)
    }

  }

  private def managerProvider: Config => SslManagersProvider = _ => new InMemSslManagersProvider
  private val sessionVerifierFactory: SslManagersProvider => SessionVerifier = _ => NoopSessionVerifier
  private val logger: MarkerLoggingAdapter = NoMarkerLogging
  private val prng = SecureRandomFactory.createSecureRandom(SecureRandomFactory.GeneratorJdkSecureRandom, logger)
  private def quickConfig(): Config =
    ConfigFactory.parseString(s"""
                                 | protocol = "TLSv1.2"
                                 | enabled-algorithms = ["TLS_DHE_RSA_WITH_AES_256_GCM_SHA384"]
                                 | require-mutual-authentication = on
                                 | hostname-verification = off
                                 | ssl-context-cache-ttl = ${cachingInMillis} ms
                                 |""".stripMargin)

}
class InMemSslManagersProvider extends SslManagersProvider {
  val trustInvocations = new AtomicInteger(0)
  val keyInvocations = new AtomicInteger(0)
  override def trustManagers: Array[TrustManager] = {
    trustInvocations.incrementAndGet()
    Array.empty[TrustManager]
  }
  override def keyManagers: Array[KeyManager] = {
    keyInvocations.incrementAndGet()
    Array.empty[KeyManager]
  }
  override val nodeCertificate: X509Certificate = null
}
