/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.{ Http, TestUtils }
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.{ HttpRequest, StatusCodes }
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.ActorMaterializer
import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, ExecutionContext, Future, Promise }
import scala.util.Try

class HttpAppSpec extends AkkaSpec with Directives with RequestBuilding with Eventually {
  import system.dispatcher

  class Minimal extends HttpApp {

    private val shutdownPromise = Promise[Done]()

    override protected def route: Route =
      path("foo") {
        complete("bar")
      } ~
        path("shutdown") {
          if (shutdownPromise.isCompleted) complete("Shutdown already in process")
          else {
            shutdownPromise.success(Done)
            complete("Shutdown request accepted")
          }
        }

    override protected def waitForShutdownSignal(system: ActorSystem)(implicit ec: ExecutionContext): Future[Done] = {
      shutdownPromise.future
    }
  }

  "HttpApp" should {

    "start without ActorSystem" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()

      val minimal = new Minimal()

      val server = Future {
        minimal.startServer(host, port, ServerSettings(ConfigFactory.load))
      }

      callAndVerify(host, port, "foo")

      // Requesting the server to shutdown
      callAndVerify(host, port, "shutdown")
      Await.ready(server, Duration(1, TimeUnit.SECONDS))
      server.isCompleted should ===(true)

    }

    "start providing an ActorSystem" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()

      val minimal = new Minimal()

      val server = Future {
        minimal.startServer(host, port, ServerSettings(system), system)
      }

      callAndVerify(host, port, "foo")

      // Requesting the server to shutdown
      callAndVerify(host, port, "shutdown")
      Await.ready(server, Duration(1, TimeUnit.SECONDS))
      server.isCompleted should ===(true)
      system.whenTerminated.isCompleted should ===(false)
    }

    "provide binding if available" in {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()

      val minimal = new Minimal()

      minimal.binding().isFailure should ===(true)

      val server = Future {
        minimal.startServer(host, port, ServerSettings(ConfigFactory.load))
      }

      callAndVerify(host, port, "foo")

      minimal.binding().isSuccess should ===(true)
      minimal.binding().get.localAddress.getPort should ===(port)
      minimal.binding().get.localAddress.getHostName should ===(host)

      // Requesting the server to shutdown
      callAndVerify(host, port, "shutdown")
      Await.ready(server, Duration(1, TimeUnit.SECONDS))
      server.isCompleted should ===(true)
    }

    "let get notified" when {
      class SneakSever extends HttpApp {

        private val shutdownPromise = Promise[Done]()

        val postBindingCalled = new AtomicBoolean(false)
        val postBindingFailureCalled = new AtomicBoolean(false)
        val postShutdownCalled = new AtomicBoolean(false)

        override protected def route: Route =
          path("foo") {
            complete("bar")
          } ~
            path("shutdown") {
              if (shutdownPromise.isCompleted) complete("Shutdown already in process")
              else {
                shutdownPromise.success(Done)
                complete("Shutdown request accepted")
              }
            }

        override protected def waitForShutdownSignal(system: ActorSystem)(implicit ec: ExecutionContext): Future[Done] = {
          shutdownPromise.future
        }

        override protected def postHttpBindingFailure(cause: Throwable): Unit = postBindingFailureCalled.set(true)

        override protected def postHttpBinding(binding: ServerBinding): Unit = postBindingCalled.set(true)

        override protected def postServerShutdown(attempt: Try[Done], system: ActorSystem): Unit = postShutdownCalled.set(true)
      }

      "shutting down" in {
        val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()

        val sneaky = new SneakSever()

        val server = Future {
          sneaky.startServer(host, port, ServerSettings(ConfigFactory.load))
        }

        sneaky.postShutdownCalled.get() should ===(false)

        // Requesting the server to shutdown
        callAndVerify(host, port, "shutdown")
        Await.ready(server, Duration(1, TimeUnit.SECONDS))
        server.isCompleted should ===(true)
        eventually {
          sneaky.postShutdownCalled.get() should ===(true)
        }
      }

      "after binding is successful" in {
        val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()

        val sneaky = new SneakSever()

        val server = Future {
          sneaky.startServer(host, port, ServerSettings(ConfigFactory.load))
        }

        callAndVerify(host, port, "foo")

        sneaky.postBindingCalled.get() should ===(true)

        // Requesting the server to shutdown
        callAndVerify(host, port, "shutdown")
        Await.ready(server, Duration(1, TimeUnit.SECONDS))
        server.isCompleted should ===(true)
      }

      "after binding is unsuccessful" in {
        val sneaky = new SneakSever()

        sneaky.startServer("localhost", 1, ServerSettings(ConfigFactory.load))

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
