/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.camel

import language.postfixOps
import language.implicitConversions

import scala.concurrent.duration._
import java.util.concurrent.{ TimeoutException, ExecutionException, TimeUnit }
import org.scalatest.{ BeforeAndAfterEach, BeforeAndAfterAll, Suite }
import org.scalatest.matchers.{ BePropertyMatcher, BePropertyMatchResult }
import scala.reflect.ClassTag
import akka.actor.{ ActorRef, Props, ActorSystem, Actor }
import scala.concurrent.Await
import akka.util.Timeout
import akka.testkit.{ TestKit, AkkaSpec }

private[camel] object TestSupport {
  def start(actor: ⇒ Actor, name: String)(implicit system: ActorSystem, timeout: Timeout): ActorRef =
    Await.result(CamelExtension(system).activationFutureFor(system.actorOf(Props(actor), name))(timeout, system.dispatcher), timeout.duration)

  def stop(actorRef: ActorRef)(implicit system: ActorSystem, timeout: Timeout): Unit = {
    system.stop(actorRef)
    Await.result(CamelExtension(system).deactivationFutureFor(actorRef)(timeout, system.dispatcher), timeout.duration)
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
    implicit lazy val system = ActorSystem("SharedCamelSystem", AkkaSpec.testConf)
    implicit lazy val camel = CamelExtension(system)

    abstract override protected def afterAll(): Unit = {
      super.afterAll()
      TestKit.shutdownActorSystem(system)
    }
  }

  trait NonSharedCamelSystem extends BeforeAndAfterEach { this: Suite ⇒
    implicit var system: ActorSystem = _
    implicit var camel: Camel = _

    override protected def beforeEach(): Unit = {
      super.beforeEach()
      system = ActorSystem("NonSharedCamelSystem", AkkaSpec.testConf)
      camel = CamelExtension(system)
    }

    override protected def afterEach(): Unit = {
      TestKit.shutdownActorSystem(system)
      super.afterEach()
    }

  }
  def time[A](block: ⇒ A): FiniteDuration = {
    val start = System.nanoTime()
    block
    val duration = System.nanoTime() - start
    duration nanos
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
