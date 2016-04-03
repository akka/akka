/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.testkit

import akka.actor.ActorSystem
import akka.http.javadsl.server._
import akka.http.scaladsl.model.HttpResponse
import akka.stream.{ Materializer, ActorMaterializer }
import org.junit.rules.ExternalResource
import org.junit.{ Assert, Rule }
import scala.concurrent.duration._
import scala.concurrent.Await
import akka.http.scaladsl.server.RouteResult

/**
 * A RouteTest that uses JUnit assertions. ActorSystem and Materializer are provided as an [[org.junit.rules.ExternalResource]]
 * and their lifetime is automatically managed.
 */
abstract class JUnitRouteTestBase extends RouteTest {
  protected def systemResource: ActorSystemResource
  implicit def system: ActorSystem = systemResource.system
  implicit def materializer: Materializer = systemResource.materializer

  protected def createTestRouteResult(result: RouteResult): TestRouteResult =
    new TestRouteResult(result, awaitDuration)(system.dispatcher, materializer) {
      protected def assertEquals(expected: AnyRef, actual: AnyRef, message: String): Unit =
        Assert.assertEquals(message, expected, actual)

      protected def assertEquals(expected: Int, actual: Int, message: String): Unit =
        Assert.assertEquals(message, expected, actual)

      protected def assertTrue(predicate: Boolean, message: String): Unit =
        Assert.assertTrue(message, predicate)

      protected def fail(message: String): Unit = {
        Assert.fail(message)
        throw new IllegalStateException("Assertion should have failed")
      }
    }
}
abstract class JUnitRouteTest extends JUnitRouteTestBase {
  private[this] val _systemResource = new ActorSystemResource
  @Rule
  protected def systemResource: ActorSystemResource = _systemResource
}

class ActorSystemResource extends ExternalResource {
  protected def createSystem(): ActorSystem = ActorSystem()
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
