/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.scalatest.BeforeAndAfterEach
import akka.util.duration._
import akka.{ Die, Ping }
import akka.testkit.TestEvent._
import akka.testkit._
import java.util.concurrent.atomic.AtomicInteger
import akka.dispatch.Await
import akka.pattern.ask

object SupervisorSpec {
  val Timeout = 5 seconds

  case object DieReply

  // =====================================================
  // Message logs
  // =====================================================

  val PingMessage = "ping"
  val PongMessage = "pong"
  val ExceptionMessage = "Expected exception; to test fault-tolerance"

  // =====================================================
  // Actors
  // =====================================================

  class PingPongActor(sendTo: ActorRef) extends Actor {
    def receive = {
      case Ping ⇒
        sendTo ! PingMessage
        if (sender != sendTo)
          sender ! PongMessage
      case Die ⇒
        throw new RuntimeException(ExceptionMessage)
      case DieReply ⇒
        val e = new RuntimeException(ExceptionMessage)
        sender ! Status.Failure(e)
        throw e
    }

    override def postRestart(reason: Throwable) {
      sendTo ! reason.getMessage
    }
  }

  class Master(sendTo: ActorRef) extends Actor {
    val temp = context.watch(context.actorOf(Props(new PingPongActor(sendTo))))

    var s: ActorRef = _

    def receive = {
      case Die                ⇒ temp forward Die
      case Terminated(`temp`) ⇒ sendTo ! "terminated"
      case Status.Failure(_)  ⇒ /*Ignore*/
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class SupervisorSpec extends AkkaSpec with BeforeAndAfterEach with ImplicitSender with DefaultTimeout {

  import SupervisorSpec._

  val TimeoutMillis = Timeout.dilated.toMillis.toInt

  // =====================================================
  // Creating actors and supervisors
  // =====================================================

  private def child(supervisor: ActorRef, props: Props): ActorRef = Await.result((supervisor ? props).mapTo[ActorRef], props.timeout.duration)

  def temporaryActorAllForOne = {
    val supervisor = system.actorOf(Props[Supervisor].withFaultHandler(AllForOneStrategy(List(classOf[Exception]), Some(0))))
    val temporaryActor = child(supervisor, Props(new PingPongActor(testActor)))

    (temporaryActor, supervisor)
  }

  def singleActorAllForOne = {
    val supervisor = system.actorOf(Props[Supervisor].withFaultHandler(AllForOneStrategy(List(classOf[Exception]), 3, TimeoutMillis)))
    val pingpong = child(supervisor, Props(new PingPongActor(testActor)))

    (pingpong, supervisor)
  }

  def singleActorOneForOne = {
    val supervisor = system.actorOf(Props[Supervisor].withFaultHandler(OneForOneStrategy(List(classOf[Exception]), 3, TimeoutMillis)))
    val pingpong = child(supervisor, Props(new PingPongActor(testActor)))

    (pingpong, supervisor)
  }

  def multipleActorsAllForOne = {
    val supervisor = system.actorOf(Props[Supervisor].withFaultHandler(AllForOneStrategy(List(classOf[Exception]), 3, TimeoutMillis)))
    val pingpong1, pingpong2, pingpong3 = child(supervisor, Props(new PingPongActor(testActor)))

    (pingpong1, pingpong2, pingpong3, supervisor)
  }

  def multipleActorsOneForOne = {
    val supervisor = system.actorOf(Props[Supervisor].withFaultHandler(OneForOneStrategy(List(classOf[Exception]), 3, TimeoutMillis)))
    val pingpong1, pingpong2, pingpong3 = child(supervisor, Props(new PingPongActor(testActor)))

    (pingpong1, pingpong2, pingpong3, supervisor)
  }

  def nestedSupervisorsAllForOne = {
    val topSupervisor = system.actorOf(Props[Supervisor].withFaultHandler(AllForOneStrategy(List(classOf[Exception]), 3, TimeoutMillis)))
    val pingpong1 = child(topSupervisor, Props(new PingPongActor(testActor)))

    val middleSupervisor = child(topSupervisor, Props[Supervisor].withFaultHandler(AllForOneStrategy(Nil, 3, TimeoutMillis)))
    val pingpong2, pingpong3 = child(middleSupervisor, Props(new PingPongActor(testActor)))

    (pingpong1, pingpong2, pingpong3, topSupervisor)
  }

  override def atStartup() {
    system.eventStream.publish(Mute(EventFilter[RuntimeException](ExceptionMessage)))
  }

  override def beforeEach() = {

  }

  def ping(pingPongActor: ActorRef) = {
    Await.result(pingPongActor.?(Ping, TimeoutMillis), TimeoutMillis millis) must be === PongMessage
    expectMsg(Timeout, PingMessage)
  }

  def kill(pingPongActor: ActorRef) = {
    val result = (pingPongActor ? (DieReply, TimeoutMillis))
    expectMsg(Timeout, ExceptionMessage)
    intercept[RuntimeException] { Await.result(result, TimeoutMillis millis) }
  }

  "A supervisor" must {

    "not restart child more times than permitted" in {
      val master = system.actorOf(Props(new Master(testActor)).withFaultHandler(OneForOneStrategy(List(classOf[Exception]), Some(0))))

      master ! Die
      expectMsg(3 seconds, "terminated")
      expectNoMsg(1 second)
    }

    "not restart temporary actor" in {
      val (temporaryActor, _) = temporaryActorAllForOne

      intercept[RuntimeException] { Await.result(temporaryActor.?(DieReply, TimeoutMillis), TimeoutMillis millis) }

      expectNoMsg(1 second)
    }

    "start server for nested supervisor hierarchy" in {
      val (actor1, _, _, _) = nestedSupervisorsAllForOne
      ping(actor1)
      expectNoMsg(1 second)
    }

    "kill single actor OneForOne" in {
      val (actor, _) = singleActorOneForOne
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
      expectMsg(Timeout, ExceptionMessage)
      expectMsg(Timeout, ExceptionMessage)
    }

    "call-kill-call multiple actors AllForOne" in {
      val (actor1, actor2, actor3, supervisor) = multipleActorsAllForOne

      ping(actor1)
      ping(actor2)
      ping(actor3)

      kill(actor2)

      // and two more exception messages
      expectMsg(Timeout, ExceptionMessage)
      expectMsg(Timeout, ExceptionMessage)

      ping(actor1)
      ping(actor2)
      ping(actor3)
    }

    "one-way kill single actor OneForOne" in {
      val (actor, _) = singleActorOneForOne

      actor ! Die
      expectMsg(Timeout, ExceptionMessage)
    }

    "one-way call-kill-call single actor OneForOne" in {
      val (actor, _) = singleActorOneForOne

      actor ! Ping
      actor ! Die
      actor ! Ping

      expectMsg(Timeout, PingMessage)
      expectMsg(Timeout, ExceptionMessage)
      expectMsg(Timeout, PingMessage)
    }

    "restart killed actors in nested superviser hierarchy" in {
      val (actor1, actor2, actor3, _) = nestedSupervisorsAllForOne

      ping(actor1)
      ping(actor2)
      ping(actor3)

      kill(actor2)

      // and two more exception messages
      expectMsg(Timeout, ExceptionMessage)
      expectMsg(Timeout, ExceptionMessage)

      ping(actor1)
      ping(actor2)
      ping(actor3)
    }

    "must attempt restart when exception during restart" in {
      val inits = new AtomicInteger(0)
      val supervisor = system.actorOf(Props[Supervisor].withFaultHandler(OneForOneStrategy(classOf[Exception] :: Nil, 3, 10000)))

      val dyingProps = Props(new Actor {
        inits.incrementAndGet

        if (inits.get % 2 == 0) throw new IllegalStateException("Don't wanna!")

        def receive = {
          case Ping ⇒ sender ! PongMessage
          case DieReply ⇒
            val e = new RuntimeException("Expected")
            sender ! Status.Failure(e)
            throw e
        }
      })
      val dyingActor = Await.result((supervisor ? dyingProps).mapTo[ActorRef], timeout.duration)

      filterEvents(EventFilter[RuntimeException]("Expected", occurrences = 1),
        EventFilter[IllegalStateException]("error while creating actor", occurrences = 1)) {
          intercept[RuntimeException] {
            Await.result(dyingActor.?(DieReply, TimeoutMillis), TimeoutMillis millis)
          }
        }

      Await.result(dyingActor.?(Ping, TimeoutMillis), TimeoutMillis millis) must be === PongMessage

      inits.get must be(3)

      system.stop(supervisor)
    }
  }
}
