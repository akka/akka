/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl

import java.util.{ Collections, Optional }
import javax.net.ssl.SSLContext

import akka.stream.TLSClientAuth
import org.scalatest.{ Matchers, WordSpec }

class ConnectionContextSpec extends WordSpec with Matchers {

  "ConnectionContext.https" should {

    "pass through all parameters" in {
      val sslContext = SSLContext.getDefault
      val ciphers: Optional[java.util.Collection[String]] = Optional.of(Collections.singletonList("A"))
      val protocols: Optional[java.util.Collection[String]] = Optional.of(Collections.singletonList("B"))
      val clientAuth = Optional.of(TLSClientAuth.need)
      val parameters = Optional.of(sslContext.getDefaultSSLParameters)

      val httpsContext = akka.http.javadsl.ConnectionContext.https(sslContext, ciphers, protocols, clientAuth, parameters)
      httpsContext.getSslContext should ===(sslContext)
      httpsContext.getEnabledCipherSuites.get.toArray.toList shouldBe (ciphers.get.toArray.toList)
      httpsContext.getEnabledProtocols.get.toArray.toList shouldBe (protocols.get.toArray.toList)
      httpsContext.getClientAuth should ===(clientAuth)
      httpsContext.getSslParameters should ===(parameters)
    }
  }
}
