/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.testkit

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.javadsl.model.HttpRequest
import akka.http.javadsl.server._
import akka.http.scaladsl.model.HttpResponse
import akka.stream.{ Materializer, ActorMaterializer }
import com.typesafe.config.{ ConfigFactory, Config }
import org.junit.rules.ExternalResource
import org.junit.{ Assert, Rule }
import org.scalatest.junit.{ JUnitSuiteLike, JUnitSuite }
import scala.concurrent.duration._
import scala.concurrent.Await

/**
 * A RouteTest that uses JUnit assertions. ActorSystem and Materializer are provided as an [[org.junit.rules.ExternalResource]]
 * and their lifetime is automatically managed.
 */
abstract class JUnitRouteTestBase extends RouteTest with JUnitSuiteLike {
  protected def systemResource: ActorSystemResource
  implicit def system: ActorSystem = systemResource.system
  implicit def materializer: Materializer = systemResource.materializer

  protected def createTestRouteResult(request: HttpRequest, result: RouteResult): TestRouteResult =
    new TestRouteResult(result, awaitDuration)(system.dispatcher, materializer) {
      protected def assertEquals(expected: AnyRef, actual: AnyRef, message: String): Unit =
        reportDetails { Assert.assertEquals(message, expected, actual) }

      protected def assertEquals(expected: Int, actual: Int, message: String): Unit =
        Assert.assertEquals(message, expected, actual)

      protected def assertTrue(predicate: Boolean, message: String): Unit =
        Assert.assertTrue(message, predicate)

      protected def fail(message: String): Unit = {
        Assert.fail(message)
        throw new IllegalStateException("Assertion should have failed")
      }

      def reportDetails[T](block: ⇒ T): T = {
        try block catch {
          case t: Throwable ⇒ throw new AssertionError(t.getMessage + "\n" +
            "  Request was:      " + request + "\n" +
            "  Route result was: " + result + "\n", t)
        }
      }
    }
}
abstract class JUnitRouteTest extends JUnitRouteTestBase {
  protected def additionalConfig: Config = ConfigFactory.empty()

  private[this] val _systemResource = new ActorSystemResource(Logging.simpleName(getClass), additionalConfig)
  @Rule
  protected def systemResource: ActorSystemResource = _systemResource
}

class ActorSystemResource(name: String, additionalConfig: Config) extends ExternalResource {
  protected def config = additionalConfig.withFallback(ConfigFactory.load())
  protected def createSystem(): ActorSystem = ActorSystem(name, config)
  protected def createMaterializer(system: ActorSystem): ActorMaterializer = ActorMaterializer()(system)

  implicit def system: ActorSystem = _system
  implicit def materializer: ActorMaterializer = _materializer

  private[this] var _system: ActorSystem = null
  private[this] var _materializer: ActorMaterializer = null

  override def before(): Unit = {
    require((_system eq null) && (_materializer eq null))
    _system = createSystem()
    _materializer = createMaterializer(_system)
  }
  override def after(): Unit = {
    Await.result(_system.terminate(), 5.seconds)
    _system = null
    _materializer = null
  }
}
