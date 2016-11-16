/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

import java.util.concurrent.TimeUnit

import akka.http.javadsl.settings.ServerSettings
import akka.http.scaladsl.{ Http, TestUtils }
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.{ HttpRequest, StatusCodes }
import akka.stream.ActorMaterializer
import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }
import scala.runtime.BoxedUnit

class HttpAppSpec extends AkkaSpec with RequestBuilding with Eventually {
  import system.dispatcher

  "HttpApp" should {

    "start without ActorSystem" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()

      val minimal = new MinimalHttpApp()

      val server = Future {
        minimal.startServer(host, port, ServerSettings.create(ConfigFactory.load))
      }

      callAndVerify(host, port, "foo")

      // Requesting the server to shutdown
      callAndVerify(host, port, "shutdown")

      Await.ready(server, Duration(1, TimeUnit.SECONDS))
      server.isCompleted should ===(true)

    }

    "start providing an ActorSystem" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()

      val minimal = new MinimalHttpApp()

      val server = Future {
        minimal.startServer(host, port, ServerSettings.create(system), system)
      }

      callAndVerify(host, port, "foo")

      // Requesting the server to shutdown
      callAndVerify(host, port, "shutdown")

      Await.ready(server, Duration(1, TimeUnit.SECONDS))
      server.isCompleted should ===(true)
      system.isTerminated should ===(false)
    }

    "provide binding if available" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()

      val minimal = new MinimalHttpApp()

      intercept[IllegalStateException] {
        minimal.binding()
      }

      val server = Future {
        minimal.startServer(host, port, ServerSettings.create(ConfigFactory.load))
      }

      callAndVerify(host, port, "foo")

      minimal.binding().localAddress.getPort should ===(port)
      minimal.binding().localAddress.getHostName should ===(host)

      // Requesting the server to shutdown
      callAndVerify(host, port, "shutdown")
      Await.ready(server, Duration(1, TimeUnit.SECONDS))
      server.isCompleted should ===(true)
    }

    "let get notified" when {

      "shutting down" in {
        val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()

        val sneaky = new SneakHttpApp()

        val server = Future {
          sneaky.startServer(host, port, ServerSettings.create(ConfigFactory.load))
        }

        sneaky.postServerShutdownCalled.get() should ===(false)

        // Requesting the server to shutdown
        callAndVerify(host, port, "shutdown")
        Await.ready(server, Duration(1, TimeUnit.SECONDS))
        server.isCompleted should ===(true)
        eventually {
          sneaky.postServerShutdownCalled.get() should ===(true)
        }
      }

      "after binding is successful" in {
        val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()

        val sneaky = new SneakHttpApp()

        val server = Future {
          sneaky.startServer(host, port, ServerSettings.create(ConfigFactory.load))
        }

        callAndVerify(host, port, "foo")

        sneaky.postBindingCalled.get() should ===(true)

        // Requesting the server to shutdown
        callAndVerify(host, port, "shutdown")
        Await.ready(server, Duration(1, TimeUnit.SECONDS))
        server.isCompleted should ===(true)
      }

      "after binding is unsuccessful" in {
        val sneaky = new SneakHttpApp()

        sneaky.startServer("localhost", 1, ServerSettings.create(ConfigFactory.load))

        eventually {
          sneaky.postBindingFailureCalled.get() should ===(true)
        }
      }

    }

  }

  private def callAndVerify(host: String, port: Int, path: String) = {

    val request = HttpRequest(uri = s"http://$host:$port/$path")

    implicit val mat = ActorMaterializer()

    eventually {
      val response = Http().singleRequest(request)
      response.futureValue.status should ===(StatusCodes.OK)
    }
  }

}
