/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterEach
import org.scalatest.BeforeAndAfterAll
import akka.testkit.Testing.sleepFor
import akka.util.duration._
import akka.{ Die, Ping }
import akka.actor.Actor._
import akka.testkit.TestEvent._
import akka.testkit.EventFilter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.LinkedBlockingQueue
import akka.testkit.AkkaSpec

object SupervisorSpec {
  val Timeout = 5 seconds
  val TimeoutMillis = Timeout.dilated.toMillis.toInt

  // =====================================================
  // Message logs
  // =====================================================

  val PingMessage = "ping"
  val PongMessage = "pong"
  val ExceptionMessage = "Expected exception; to test fault-tolerance"

  var messageLog = new LinkedBlockingQueue[String]

  def messageLogPoll = messageLog.poll(Timeout.length, Timeout.unit)

  // =====================================================
  // Actors
  // =====================================================

  class PingPongActor extends Actor {
    def receive = {
      case Ping ⇒
        messageLog.put(PingMessage)
        tryReply(PongMessage)
      case Die ⇒
        throw new RuntimeException(ExceptionMessage)
    }

    override def postRestart(reason: Throwable) {
      messageLog.put(reason.getMessage)
    }
  }

  class Master extends Actor {

    val temp = context.actorOf(Props[PingPongActor])
    self startsMonitoring temp
    var s: UntypedChannel = _

    def receive = {
      case Die                       ⇒ temp ! Die; s = context.channel
      case Terminated(`temp`, cause) ⇒ s ! cause
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class SupervisorSpec extends AkkaSpec with BeforeAndAfterEach {

  import SupervisorSpec._

  // =====================================================
  // Creating actors and supervisors
  // =====================================================

  private def child(supervisor: ActorRef, props: Props): ActorRef = (supervisor ? props).as[ActorRef].get

  def temporaryActorAllForOne = {
    val supervisor = actorOf(Props[Supervisor].withFaultHandler(AllForOneStrategy(List(classOf[Exception]), Some(0))))
    val temporaryActor = child(supervisor, Props[PingPongActor])

    (temporaryActor, supervisor)
  }

  def singleActorAllForOne = {
    val supervisor = actorOf(Props[Supervisor].withFaultHandler(AllForOneStrategy(List(classOf[Exception]), 3, TimeoutMillis)))
    val pingpong = child(supervisor, Props[PingPongActor])

    (pingpong, supervisor)
  }

  def singleActorOneForOne = {
    val supervisor = actorOf(Props[Supervisor].withFaultHandler(OneForOneStrategy(List(classOf[Exception]), 3, TimeoutMillis)))
    val pingpong = child(supervisor, Props[PingPongActor])

    (pingpong, supervisor)
  }

  def multipleActorsAllForOne = {
    val supervisor = actorOf(Props[Supervisor].withFaultHandler(AllForOneStrategy(List(classOf[Exception]), 3, TimeoutMillis)))
    val pingpong1, pingpong2, pingpong3 = child(supervisor, Props[PingPongActor])

    (pingpong1, pingpong2, pingpong3, supervisor)
  }

  def multipleActorsOneForOne = {
    val supervisor = actorOf(Props[Supervisor].withFaultHandler(OneForOneStrategy(List(classOf[Exception]), 3, TimeoutMillis)))
    val pingpong1, pingpong2, pingpong3 = child(supervisor, Props[PingPongActor])

    (pingpong1, pingpong2, pingpong3, supervisor)
  }

  def nestedSupervisorsAllForOne = {
    val topSupervisor = actorOf(Props[Supervisor].withFaultHandler(AllForOneStrategy(List(classOf[Exception]), 3, TimeoutMillis)))
    val pingpong1 = child(topSupervisor, Props[PingPongActor])

    val middleSupervisor = child(topSupervisor, Props[Supervisor].withFaultHandler(AllForOneStrategy(Nil, 3, TimeoutMillis)))
    val pingpong2, pingpong3 = child(middleSupervisor, Props[PingPongActor])

    (pingpong1, pingpong2, pingpong3, topSupervisor)
  }

  override def atStartup() = {
    app.eventHandler notify Mute(EventFilter[Exception]("Die"),
      EventFilter[IllegalStateException]("Don't wanna!"),
      EventFilter[RuntimeException]("Expected"))
  }

  override def atTermination() = {
    app.eventHandler notify UnMuteAll
  }

  override def beforeEach() = {
    messageLog.clear
  }

  def ping(pingPongActor: ActorRef) = {
    (pingPongActor.?(Ping, TimeoutMillis)).as[String].getOrElse("nil") must be === PongMessage
    messageLogPoll must be === PingMessage
  }

  def kill(pingPongActor: ActorRef) = {
    intercept[RuntimeException] { (pingPongActor ? (Die, TimeoutMillis)).as[Any] }
    messageLogPoll must be === ExceptionMessage
  }

  "A supervisor" must {

    "not restart programmatically linked temporary actor" in {
      val master = actorOf(Props[Master].withFaultHandler(OneForOneStrategy(List(classOf[Exception]), Some(0))))

      (master.?(Die, TimeoutMillis)).get match {
        case r: RuntimeException ⇒ r === ExceptionMessage
      }

      sleepFor(1 second)
      messageLogPoll must be(null)
    }

    "not restart temporary actor" in {
      val (temporaryActor, supervisor) = temporaryActorAllForOne

      intercept[RuntimeException] {
        (temporaryActor.?(Die, TimeoutMillis)).get
      }

      sleepFor(1 second)
      messageLog.size must be(0)
    }

    "start server for nested supervisor hierarchy" in {
      val (actor1, actor2, actor3, supervisor) = nestedSupervisorsAllForOne
      ping(actor1)
    }

    "kill single actor OneForOne" in {
      val (actor, supervisor) = singleActorOneForOne
      kill(actor)
    }

    "call-kill-call single actor OneForOne" in {
      val (actor, supervisor) = singleActorOneForOne
      ping(actor)
      kill(actor)
      ping(actor)
    }

    "kill single actor AllForOne" in {
      val (actor, supervisor) = singleActorAllForOne
      kill(actor)
    }

    "call-kill-call single actor AllForOne" in {
      val (actor, supervisor) = singleActorAllForOne
      ping(actor)
      kill(actor)
      ping(actor)
    }

    "kill multiple actors OneForOne 1" in {
      val (actor1, actor2, actor3, supervisor) = multipleActorsOneForOne
      kill(actor1)
    }

    "kill multiple actors OneForOne 2" in {
      val (actor1, actor2, actor3, supervisor) = multipleActorsOneForOne
      kill(actor3)
    }

    "call-kill-call multiple actors OneForOne" in {
      val (actor1, actor2, actor3, supervisor) = multipleActorsOneForOne

      ping(actor1)
      ping(actor2)
      ping(actor3)

      kill(actor2)

      ping(actor1)
      ping(actor2)
      ping(actor3)
    }

    "kill multiple actors AllForOne" in {
      val (actor1, actor2, actor3, supervisor) = multipleActorsAllForOne

      kill(actor2)

      // and two more exception messages
      messageLogPoll must be(ExceptionMessage)
      messageLogPoll must be(ExceptionMessage)
    }

    "call-kill-call multiple actors AllForOne" in {
      val (actor1, actor2, actor3, supervisor) = multipleActorsAllForOne

      ping(actor1)
      ping(actor2)
      ping(actor3)

      kill(actor2)

      // and two more exception messages
      messageLogPoll must be(ExceptionMessage)
      messageLogPoll must be(ExceptionMessage)

      ping(actor1)
      ping(actor2)
      ping(actor3)
    }

    "one-way kill single actor OneForOne" in {
      val (actor, supervisor) = singleActorOneForOne

      actor ! Die
      messageLogPoll must be(ExceptionMessage)
    }

    "one-way call-kill-call single actor OneForOne" in {
      val (actor, supervisor) = singleActorOneForOne

      actor ! Ping
      messageLogPoll must be(PingMessage)

      actor ! Die
      messageLogPoll must be(ExceptionMessage)

      actor ! Ping
      messageLogPoll must be(PingMessage)
    }

    "restart killed actors in nested superviser hierarchy" in {
      val (actor1, actor2, actor3, supervisor) = nestedSupervisorsAllForOne

      ping(actor1)
      ping(actor2)
      ping(actor3)

      kill(actor2)

      // and two more exception messages
      messageLogPoll must be(ExceptionMessage)
      messageLogPoll must be(ExceptionMessage)

      ping(actor1)
      ping(actor2)
      ping(actor3)
    }

    "must attempt restart when exception during restart" in {
      val inits = new AtomicInteger(0)
      val supervisor = actorOf(Props[Supervisor].withFaultHandler(OneForOneStrategy(classOf[Exception] :: Nil, 3, 10000)))

      val dyingProps = Props(new Actor {
        inits.incrementAndGet

        if (inits.get % 2 == 0) throw new IllegalStateException("Don't wanna!")

        def receive = {
          case Ping ⇒ tryReply(PongMessage)
          case Die  ⇒ throw new RuntimeException("Expected")
        }
      })
      val dyingActor = (supervisor ? dyingProps).as[ActorRef].get

      intercept[RuntimeException] {
        (dyingActor.?(Die, TimeoutMillis)).get
      }

      // give time for restart
      sleepFor(3 seconds)

      (dyingActor.?(Ping, TimeoutMillis)).as[String].getOrElse("nil") must be === PongMessage

      inits.get must be(3)

      supervisor.stop()
    }
  }
}
