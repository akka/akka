/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server

//#imports
import java.io.InputStream
import java.security.{ SecureRandom, KeyStore }
import javax.net.ssl.{ SSLContext, TrustManagerFactory, KeyManagerFactory }

import akka.actor.ActorSystem
import akka.http.scaladsl.server.{ Route, Directives }
import akka.http.scaladsl.{ ConnectionContext, HttpsConnectionContext, Http }
import akka.stream.ActorMaterializer
import com.typesafe.sslconfig.akka.AkkaSSLConfig
//#imports

import docs.CompileOnlySpec
import org.scalatest.{ Matchers, WordSpec }

abstract class HttpsServerExampleSpec extends WordSpec with Matchers
  with Directives with CompileOnlySpec {

  class HowToObtainSSLConfig {
    //#akka-ssl-config
    implicit val system = ActorSystem()
    val sslConfig = AkkaSSLConfig()
    //#akka-ssl-config
  }

  "low level api" in compileOnlySpec {
    //#low-level-default
    implicit val system = ActorSystem()
    implicit val mat = ActorMaterializer()
    implicit val dispatcher = system.dispatcher

    // Manual HTTPS configuration

    val password: Array[Char] = "change me".toCharArray // do not store passwords in code, read them from somewhere safe!

    val ks: KeyStore = KeyStore.getInstance("PKCS12")
    val keystore: InputStream = getClass.getClassLoader.getResourceAsStream("server.p12")

    require(keystore != null, "Keystore required!")
    ks.load(keystore, password)

    val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(ks, password)

    val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    tmf.init(ks)

    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)
    val https: HttpsConnectionContext = ConnectionContext.https(sslContext)
    //#low-level-default

    //#both-https-and-http
    // you can run both HTTP and HTTPS in the same application as follows:
    val commonRoutes: Route = get { complete("Hello world!") }
    Http().bindAndHandle(commonRoutes, "127.0.0.1", 443, connectionContext = https)
    Http().bindAndHandle(commonRoutes, "127.0.0.1", 80)
    //#both-https-and-http

    //#bind-low-level-context
    Http().bind("127.0.0.1", connectionContext = https)

    // or using the high level routing DSL:
    val routes: Route = get { complete("Hello world!") }
    Http().bindAndHandle(routes, "127.0.0.1", 8080, connectionContext = https)
    //#bind-low-level-context

    //#set-low-level-context-default
    // sets default context to HTTPS – all Http() bound servers for this ActorSystem will use HTTPS from now on
    Http().setDefaultServerHttpContext(https)
    Http().bindAndHandle(routes, "127.0.0.1", 9090, connectionContext = https)
    //#set-low-level-context-default

    system.terminate()
  }

}
