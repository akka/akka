/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.actor.{ ActorLogging, ActorSystem }
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.util.ByteString
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import org.scalatest.{ Matchers, WordSpec }

class HttpsExamplesSpec extends WordSpec with Matchers {

  "disable SNI for connection" in {
    pending // compile-time only test

    val unsafeHost = "example.com"
    //#disable-sni-connection
    implicit val system = ActorSystem()
    implicit val mat = ActorMaterializer()

    // WARNING: disabling SNI is a very bad idea, please don't unless you have a very good reason to.
    val badSslConfig = AkkaSSLConfig().mapSettings(s => s.withLoose(s.loose.withDisableSNI(true)))
    val badCtx = Http().createClientHttpsContext(badSslConfig)
    Http().outgoingConnectionHttps(unsafeHost, connectionContext = badCtx)
    //#disable-sni-connection
  }
}