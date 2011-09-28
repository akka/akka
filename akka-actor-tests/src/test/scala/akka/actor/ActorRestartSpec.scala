/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.{ WordSpec, BeforeAndAfterAll, BeforeAndAfterEach }
import org.scalatest.matchers.MustMatchers

import Actor.actorOf
import akka.testkit._
import akka.util.duration._

import java.util.concurrent.atomic._

object ActorRestartSpec {

  private var _gen = new AtomicInteger(0)
  def generation = _gen.incrementAndGet
  def generation_=(x: Int) { _gen.set(x) }

  sealed trait RestartType
  case object Normal extends RestartType
  case object Nested extends RestartType
  case object Handover extends RestartType
  case object Fail extends RestartType

  class Restarter(val testActor: ActorRef) extends Actor {
    val gen = generation
    var xx = 0
    var restart: RestartType = Normal
    def receive = {
      case x: Int         ⇒ xx = x
      case t: RestartType ⇒ restart = t
      case "get"          ⇒ reply(xx)
    }
    override def preStart { testActor ! (("preStart", gen)) }
    override def preRestart(cause: Throwable, msg: Option[Any]) { testActor ! (("preRestart", msg, gen)) }
    override def postRestart(cause: Throwable) { testActor ! (("postRestart", gen)) }
  }

  class Supervisor extends Actor {
    def receive = {
      case _ ⇒
    }
  }
}

class ActorRestartSpec extends WordSpec with MustMatchers with TestKit with BeforeAndAfterEach {
  import ActorRestartSpec._

  override def beforeEach { generation = 0 }
  override def afterEach {
    val it = toStop.iterator
    while (it.hasNext) {
      try { it.next.stop() } catch { case _: akka.actor.ActorInitializationException ⇒ } //FIXME thrown because supervisor is stopped before
      it.remove
    }
  }

  private var toStop = new java.util.concurrent.ConcurrentSkipListSet[ActorRef]
  private def collect(f: ⇒ ActorRef): ActorRef = {
    val ref = f
    toStop add ref
    ref
  }

  private def createSupervisor =
    actorOf(Props[Supervisor].withFaultHandler(OneForOneStrategy(List(classOf[Throwable]), 5, 5000)))

  val expectedEvents = Seq(EventFilter[ActorKilledException], EventFilter[IllegalActorStateException]("expected"))

  "An Actor restart" must {

    "invoke preRestart, preStart, postRestart" in {
      filterEvents(expectedEvents) {
        val supervisor = collect(createSupervisor)
        val actor = collect(actorOf(Props(new Restarter(testActor)) withSupervisor (supervisor)))
        expectMsg(1 second, ("preStart", 1))
        actor ! Kill
        within(1 second) {
          expectMsg(("preRestart", Some(Kill), 1))
          expectMsg(("postRestart", 2))
          expectNoMsg
        }
      }
    }
  }

}
