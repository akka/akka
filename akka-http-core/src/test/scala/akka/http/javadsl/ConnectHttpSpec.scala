/**
 * Copyright (C) 2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.http.javadsl

import akka.http.javadsl.model._
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }

class ConnectHttpSpec extends WordSpec with Matchers with BeforeAndAfterAll {

  val httpContext = ConnectionContext.noEncryption()
  val httpsContext = ConnectionContext.https(null)

  val successResponse = HttpResponse.create().withStatus(200)

  "HttpConnect" should {

    "connect toHost HTTP:80 by default" in {
      val connect = ConnectHttp.toHost("127.0.0.1")
      connect.isHttps should ===(false)
      connect.connectionContext.isPresent should equal(false)
      connect.host should ===("127.0.0.1")
      connect.port should ===(80)
    }
    "connect toHost HTTPS:443 when https prefix given in host" in {
      val connect = ConnectHttp.toHost("https://127.0.0.1")
      connect.isHttps should ===(true)
      connect.connectionContext.isPresent should equal(false)
      connect.host should ===("127.0.0.1")
      connect.port should ===(443)
    }
    "connect toHostHttps HTTPS:8080 when https prefix and port given in host" in {
      val connect = ConnectHttp.toHostHttps("https://127.0.0.1:8080")
      connect.isHttps should ===(true)
      connect.connectionContext.isPresent should equal(false)
      connect.host should ===("127.0.0.1")
      connect.port should ===(8080)
    }
    "connect toHostHttps HTTPS:9999 when https prefix and port given" in {
      val connect = ConnectHttp.toHostHttps("https://127.0.0.1:8080", 9999)
      connect.isHttps should ===(true)
      connect.connectionContext.isPresent should equal(false)
      connect.host should ===("127.0.0.1")
      connect.port should ===(9999)
    }
    "connect toHost HTTPS:8080 when https prefix and port given" in {
      val connect = ConnectHttp.toHost("http://127.0.0.1:8080", 9999)
      connect.isHttps should ===(false)
      connect.connectionContext.isPresent should equal(false)
      connect.host should ===("127.0.0.1")
      connect.port should ===(9999)
    }
    "connect toHostHttps HTTPS:443 when no port given" in {
      val connect = ConnectHttp.toHostHttps("https://127.0.0.1")
      connect.isHttps should ===(true)
      connect.connectionContext.isPresent should equal(false)
      connect.host should ===("127.0.0.1")
      connect.port should ===(443)
    }
    "connect toHostHttps HTTPS:443 using custom https context" in {
      val connect = ConnectHttp.toHostHttps("https://127.0.0.1").withCustomHttpsContext(httpsContext)
      connect.isHttps should ===(true)
      connect.connectionContext.isPresent should equal(true)
      connect.host should ===("127.0.0.1")
      connect.port should ===(443)
    }
    "throw when toHostHttps used but http:// prefix found" in {
      val ex = intercept[IllegalArgumentException] {
        ConnectHttp.toHostHttps("http://127.0.0.1", 8080)
      }
      ex.getMessage should include("non https scheme!")
    }
  }
}
