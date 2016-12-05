/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import docs.CompileOnlySpec
import org.scalatest.{ Matchers, WordSpec }

class HttpsExamplesSpec extends WordSpec with Matchers with CompileOnlySpec {

  "disable SNI for connection" in compileOnlySpec {
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
