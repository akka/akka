/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import akka.config.Configuration
import org.scalatest.{ WordSpec, BeforeAndAfterAll }
import org.scalatest.matchers.MustMatchers
import akka.AkkaApplication
import akka.actor.{ Actor, ActorRef, Props }
import akka.dispatch.MessageDispatcher
import akka.event.Logging
import akka.util.duration._
import akka.dispatch.FutureTimeoutException

abstract class AkkaSpec(_application: AkkaApplication = AkkaApplication())
  extends TestKit(_application) with WordSpec with MustMatchers with BeforeAndAfterAll {

  val log: Logging = Logging(app.mainbus, this)

  final override def beforeAll {
    atStartup()
  }

  final override def afterAll {
    app.stop()
    try app.terminationFuture.await(5 seconds) catch {
      case _: FutureTimeoutException ⇒ app.log.warning("failed to stop within 5 seconds")
    }
    atTermination()
  }

  protected def atStartup() {}

  protected def atTermination() {}

  def this(config: Configuration) = this(new AkkaApplication(getClass.getSimpleName, AkkaApplication.defaultConfig ++ config))

  def actorOf(props: Props): ActorRef = app.actorOf(props)

  def actorOf[T <: Actor](clazz: Class[T]): ActorRef = actorOf(Props(clazz))

  def actorOf[T <: Actor: Manifest]: ActorRef = actorOf(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]])

  def actorOf[T <: Actor](factory: ⇒ T): ActorRef = actorOf(Props(factory))

  def spawn(body: ⇒ Unit)(implicit dispatcher: MessageDispatcher) {
    actorOf(Props(ctx ⇒ { case "go" ⇒ try body finally ctx.self.stop() }).withDispatcher(dispatcher)) ! "go"
  }
}

class AkkaSpecSpec extends WordSpec with MustMatchers {
  "An AkkaSpec" must {
    "terminate all actors" in {
      import AkkaApplication.defaultConfig
      val app = AkkaApplication("test", defaultConfig ++ Configuration(
        "akka.actor.debug.lifecycle" -> true, "akka.loglevel" -> "DEBUG"))
      val spec = new AkkaSpec(app) {
        val ref = Seq(testActor, app.actorOf(Props.empty, "name"))
      }
      spec.ref foreach (_ must not be 'shutdown)
      app.stop()
      spec.awaitCond(spec.ref forall (_.isShutdown), 2 seconds)
    }
  }
}