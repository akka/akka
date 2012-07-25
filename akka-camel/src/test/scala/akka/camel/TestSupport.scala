/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import language.postfixOps
import language.implicitConversions

import scala.concurrent.util.duration._
import java.util.concurrent.{ TimeoutException, ExecutionException, TimeUnit }
import org.scalatest.{ BeforeAndAfterEach, BeforeAndAfterAll, Suite }
import org.scalatest.matchers.{ BePropertyMatcher, BePropertyMatchResult }
import scala.concurrent.util.{ FiniteDuration, Duration }
import scala.reflect.ClassTag
import akka.actor.{ ActorRef, Props, ActorSystem, Actor }
import concurrent.Await
import akka.util.Timeout

private[camel] object TestSupport {

  def start(actor: ⇒ Actor)(implicit system: ActorSystem): ActorRef = {
    val actorRef = system.actorOf(Props(actor))
    Await.result(CamelExtension(system).activationFutureFor(actorRef)(10 seconds), 10 seconds)
    actorRef
  }

  private[camel] implicit def camelToTestWrapper(camel: Camel) = new CamelTestWrapper(camel)

  class CamelTestWrapper(camel: Camel) {
    /**
     * Sends msg to the endpoint and returns response.
     * It only waits for the response until timeout passes.
     * This is to reduce cases when unit-tests block infinitely.
     */
    def sendTo(to: String, msg: String, timeout: Duration = 1 second): AnyRef = {
      try {
        camel.template.asyncRequestBody(to, msg).get(timeout.toNanos, TimeUnit.NANOSECONDS)
      } catch {
        case e: ExecutionException ⇒ throw e.getCause
        case e: TimeoutException   ⇒ throw new AssertionError("Failed to get response to message [%s], send to endpoint [%s], within [%s]".format(msg, to, timeout))
      }
    }

    def routeCount = camel.context.getRoutes().size()
    def routes = camel.context.getRoutes
  }

  trait SharedCamelSystem extends BeforeAndAfterAll { this: Suite ⇒
    implicit lazy val system = ActorSystem("test")
    implicit lazy val camel = CamelExtension(system)

    abstract override protected def afterAll() {
      super.afterAll()
      system.shutdown()
    }
  }

  trait NonSharedCamelSystem extends BeforeAndAfterEach { this: Suite ⇒
    implicit var system: ActorSystem = _
    implicit var camel: Camel = _

    override protected def beforeEach() {
      super.beforeEach()
      system = ActorSystem("test")
      camel = CamelExtension(system)
    }

    override protected def afterEach() {
      system.shutdown()
      super.afterEach()
    }

  }
  def time[A](block: ⇒ A): FiniteDuration = {
    val start = System.currentTimeMillis()
    block
    val duration = System.currentTimeMillis() - start
    duration millis
  }

  def anInstanceOf[T](implicit tag: ClassTag[T]) = {
    val clazz = tag.runtimeClass.asInstanceOf[Class[T]]
    new BePropertyMatcher[AnyRef] {
      def apply(left: AnyRef) = BePropertyMatchResult(
        clazz.isAssignableFrom(left.getClass),
        "an instance of " + clazz.getName)
    }
  }

}
