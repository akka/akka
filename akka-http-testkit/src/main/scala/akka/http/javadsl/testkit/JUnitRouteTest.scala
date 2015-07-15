/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.testkit

import akka.actor.ActorSystem
import akka.http.javadsl.server._
import akka.http.scaladsl.model.HttpResponse
import akka.stream.ActorMaterializer
import org.junit.rules.ExternalResource
import org.junit.{ Assert, Rule }

import scala.concurrent.duration._

/**
 * A RouteTest that uses JUnit assertions.
 */
abstract class JUnitRouteTestBase extends RouteTest {
  protected def systemResource: ActorSystemResource
  implicit def system: ActorSystem = systemResource.system
  implicit def materializer: ActorMaterializer = systemResource.materializer

  protected def createTestResponse(response: HttpResponse): TestResponse =
    new TestResponse(response, awaitDuration)(system.dispatcher, materializer) {
      protected def assertEquals(expected: AnyRef, actual: AnyRef, message: String): Unit =
        Assert.assertEquals(message, expected, actual)

      protected def assertEquals(expected: Int, actual: Int, message: String): Unit =
        Assert.assertEquals(message, expected, actual)

      protected def assertTrue(predicate: Boolean, message: String): Unit =
        Assert.assertTrue(message, predicate)

      protected def fail(message: String): Nothing = {
        Assert.fail(message)
        throw new IllegalStateException("Assertion should have failed")
      }
    }

  protected def completeWithValueToString[T](value: RequestVal[T]): Route =
    handleWith1(value, new Handler1[T] {
      def apply(ctx: RequestContext, t: T): RouteResult = ctx.complete(t.toString)
    })
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
    _system.shutdown()
    _system.awaitTermination(5.seconds)
    _system = null
    _materializer = null
  }
}
