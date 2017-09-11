/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.scaladsl

import docs.CompileOnlySpec
import org.scalatest.{ Matchers, WordSpec }

class HttpAppExampleSpec extends WordSpec with Matchers
  with CompileOnlySpec {

  "minimal-routing-example" in compileOnlySpec {
    //#minimal-routing-example
    import akka.http.scaladsl.model.{ ContentTypes, HttpEntity }
    import akka.http.scaladsl.server.HttpApp
    import akka.http.scaladsl.server.Route

    // Server definition
    object WebServer extends HttpApp {
      override def routes: Route =
        path("hello") {
          get {
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
          }
        }
    }

    // Starting the server
    WebServer.startServer("localhost", 8080)
    //#minimal-routing-example
  }

  "with-settings-routing-example" in compileOnlySpec {
    //#with-settings-routing-example
    import akka.http.scaladsl.model.{ ContentTypes, HttpEntity }
    import akka.http.scaladsl.server.HttpApp
    import akka.http.scaladsl.server.Route
    import akka.http.scaladsl.settings.ServerSettings
    import com.typesafe.config.ConfigFactory

    // Server definition
    object WebServer extends HttpApp {
      override def routes: Route =
        path("hello") {
          get {
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
          }
        }
    }

    // Creating own settings
    val settings = ServerSettings(ConfigFactory.load).withVerboseErrorMessages(true)
    WebServer.startServer("localhost", 8080, settings)
    //#with-settings-routing-example
  }

  "webserver-as-providing-actor-system" in compileOnlySpec {
    //#with-actor-system
    import akka.actor.ActorSystem
    import akka.http.scaladsl.model._
    import akka.http.scaladsl.server.HttpApp
    import akka.http.scaladsl.server.Route

    // Server definition
    object WebServer extends HttpApp {
      override def routes: Route =
        path("hello") {
          get {
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
          }
        }
    }

    // Starting the server
    val system = ActorSystem("ownActorSystem")
    WebServer.startServer("localhost", 8080, system)
    system.terminate()
    //#with-actor-system
  }

  "webserver-as-providing-actor-system-and-settings" in compileOnlySpec {
    //#with-actor-system-settings
    import akka.actor.ActorSystem
    import akka.http.scaladsl.model._
    import akka.http.scaladsl.server.HttpApp
    import akka.http.scaladsl.server.Route
    import akka.http.scaladsl.settings.ServerSettings
    import com.typesafe.config.ConfigFactory

    // Server definition
    object WebServer extends HttpApp {
      override def routes: Route =
        path("hello") {
          get {
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
          }
        }
    }

    // Starting the server
    val system = ActorSystem("ownActorSystem")
    val settings = ServerSettings(ConfigFactory.load).withVerboseErrorMessages(true)
    WebServer.startServer("localhost", 8080, settings, system)
    system.terminate()
    //#with-actor-system-settings
  }

  "failed-binding" in compileOnlySpec {
    //#failed-binding-example
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model._
    import akka.http.scaladsl.server.HttpApp
    import akka.http.scaladsl.server.Route
    import akka.http.scaladsl.settings.ServerSettings
    import com.typesafe.config.ConfigFactory

    // Server definition
    object WebServer extends HttpApp {
      override def routes: Route =
        path("hello") {
          get {
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
          }
        }

      override protected def postHttpBinding(binding: Http.ServerBinding): Unit = {
        super.postHttpBinding(binding)
        val sys = systemReference.get()
        sys.log.info(s"Running on [${sys.name}] actor system")
      }

      override protected def postHttpBindingFailure(cause: Throwable): Unit = {
        println(s"The server could not be started due to $cause")
      }
    }

    // Starting the server
    WebServer.startServer("localhost", 80, ServerSettings(ConfigFactory.load))
    //#failed-binding-example
  }

  "minimal-routing-example self destroying in 5 seconds" in compileOnlySpec {
    //#override-termination-signal
    import akka.Done
    import akka.actor.ActorSystem
    import akka.http.scaladsl.model._
    import akka.http.scaladsl.server.HttpApp
    import akka.pattern
    import akka.http.scaladsl.server.Route
    import akka.http.scaladsl.settings.ServerSettings
    import com.typesafe.config.ConfigFactory
    import scala.concurrent.duration._
    import scala.concurrent.{ ExecutionContext, Future }
    import scala.language.postfixOps

    // Server definition
    object WebServer extends HttpApp {
      override def routes: Route =
        path("hello") {
          get {
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
          }
        }

      override def waitForShutdownSignal(actorSystem: ActorSystem)(implicit executionContext: ExecutionContext): Future[Done] = {
        pattern.after(5 seconds, actorSystem.scheduler)(Future.successful(Done))
      }
    }

    // Starting the server
    WebServer.startServer("localhost", 8080, ServerSettings(ConfigFactory.load))
    //#override-termination-signal
  }

  "stopping-system-on-shutdown" in compileOnlySpec {
    //#cleanup-after-shutdown
    import akka.Done
    import akka.actor.ActorSystem
    import akka.http.scaladsl.model._
    import akka.http.scaladsl.server.HttpApp
    import akka.http.scaladsl.server.Route
    import akka.http.scaladsl.settings.ServerSettings
    import scala.util.Try
    import com.typesafe.config.ConfigFactory

    // Server definition
    class WebServer extends HttpApp {
      override def routes: Route =
        path("hello") {
          get {
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
          }
        }

      private def cleanUpResources(): Unit = ???

      override def postServerShutdown(attempt: Try[Done], system: ActorSystem): Unit = {
        cleanUpResources()
      }
    }

    // Starting the server
    new WebServer().startServer("localhost", 8080, ServerSettings(ConfigFactory.load))
    //#cleanup-after-shutdown
  }

}
