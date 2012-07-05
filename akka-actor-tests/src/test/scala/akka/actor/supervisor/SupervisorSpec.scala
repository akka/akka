/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterEach

import akka.testkit._
import akka.testkit.Testing.sleepFor
import akka.util.duration._
import akka.config.Supervision._
import akka.{ Die, Ping }
import Actor._

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.LinkedBlockingQueue

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
        self.tryReply(PongMessage)
      case Die ⇒
        throw new RuntimeException(ExceptionMessage)
    }

    override def postRestart(reason: Throwable) {
      messageLog.put(reason.getMessage)
    }
  }

  class TemporaryActor extends PingPongActor {
    self.lifeCycle = Temporary
  }

  class Master extends Actor {
    self.faultHandler = OneForOneStrategy(List(classOf[Exception]), 5, (1 second).dilated.toMillis.toInt)

    val temp = self.spawnLink[TemporaryActor]

    override def receive = {
      case Die ⇒ temp !! (Die, TimeoutMillis)
    }
  }

  // =====================================================
  // Creating actors and supervisors
  // =====================================================

  def temporaryActorAllForOne = {
    val temporaryActor = actorOf[TemporaryActor].start()

    val supervisor = Supervisor(
      SupervisorConfig(
        AllForOneStrategy(List(classOf[Exception]), 3, TimeoutMillis),
        Supervise(
          temporaryActor,
          Temporary)
          :: Nil))

    (temporaryActor, supervisor)
  }

  def singleActorAllForOne = {
    val pingpong = actorOf[PingPongActor].start()

    val supervisor = Supervisor(
      SupervisorConfig(
        AllForOneStrategy(List(classOf[Exception]), 3, TimeoutMillis),
        Supervise(
          pingpong,
          Permanent)
          :: Nil))

    (pingpong, supervisor)
  }

  def singleActorOneForOne = {
    val pingpong = actorOf[PingPongActor].start()

    val supervisor = Supervisor(
      SupervisorConfig(
        OneForOneStrategy(List(classOf[Exception]), 3, TimeoutMillis),
        Supervise(
          pingpong,
          Permanent)
          :: Nil))

    (pingpong, supervisor)
  }

  def multipleActorsAllForOne = {
    val pingpong1 = actorOf[PingPongActor].start()
    val pingpong2 = actorOf[PingPongActor].start()
    val pingpong3 = actorOf[PingPongActor].start()

    val supervisor = Supervisor(
      SupervisorConfig(
        AllForOneStrategy(List(classOf[Exception]), 3, TimeoutMillis),
        Supervise(
          pingpong1,
          Permanent)
          ::
          Supervise(
            pingpong2,
            Permanent)
            ::
            Supervise(
              pingpong3,
              Permanent)
              :: Nil))

    (pingpong1, pingpong2, pingpong3, supervisor)
  }

  def multipleActorsOneForOne = {
    val pingpong1 = actorOf[PingPongActor].start()
    val pingpong2 = actorOf[PingPongActor].start()
    val pingpong3 = actorOf[PingPongActor].start()

    val supervisor = Supervisor(
      SupervisorConfig(
        OneForOneStrategy(List(classOf[Exception]), 3, TimeoutMillis),
        Supervise(
          pingpong1,
          Permanent)
          ::
          Supervise(
            pingpong2,
            Permanent)
            ::
            Supervise(
              pingpong3,
              Permanent)
              :: Nil))

    (pingpong1, pingpong2, pingpong3, supervisor)
  }

  def nestedSupervisorsAllForOne = {
    val pingpong1 = actorOf[PingPongActor]
    val pingpong2 = actorOf[PingPongActor]
    val pingpong3 = actorOf[PingPongActor]

    val supervisor = Supervisor(
      SupervisorConfig(
        AllForOneStrategy(List(classOf[Exception]), 3, TimeoutMillis),
        Supervise(
          pingpong1,
          Permanent)
          ::
          SupervisorConfig(
            AllForOneStrategy(Nil, 3, TimeoutMillis),
            Supervise(
              pingpong2,
              Permanent)
              ::
              Supervise(
                pingpong3,
                Permanent)
                :: Nil)
            :: Nil))

    (pingpong1, pingpong2, pingpong3, supervisor)
  }
}

class SupervisorSpec extends WordSpec with MustMatchers with BeforeAndAfterEach {
  import SupervisorSpec._

  override def beforeEach() = {
    messageLog.clear
  }

  def ping(pingPongActor: ActorRef) = {
    (pingPongActor !! (Ping, TimeoutMillis)).getOrElse("nil") must be(PongMessage)
    messageLogPoll must be(PingMessage)
  }

  def kill(pingPongActor: ActorRef) = {
    intercept[RuntimeException] { pingPongActor !! (Die, TimeoutMillis) }
    messageLogPoll must be(ExceptionMessage)
  }

  "A supervisor" must {

    "not restart programmatically linked temporary actor" in {
      val master = actorOf[Master].start()

      intercept[RuntimeException] {
        master !! (Die, TimeoutMillis)
      }

      sleepFor(1 second)
      messageLog.size must be(0)
    }

    "not restart temporary actor" in {
      val (temporaryActor, supervisor) = temporaryActorAllForOne

      intercept[RuntimeException] {
        temporaryActor !! (Die, TimeoutMillis)
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

      val dyingActor = actorOf(new Actor {
        self.lifeCycle = Permanent
        inits.incrementAndGet

        if (inits.get % 2 == 0) throw new IllegalStateException("Don't wanna!")

        def receive = {
          case Ping ⇒ self.tryReply(PongMessage)
          case Die  ⇒ throw new Exception("expected")
        }
      })

      val supervisor =
        Supervisor(
          SupervisorConfig(
            OneForOneStrategy(classOf[Exception] :: Nil, 3, 10000),
            Supervise(dyingActor, Permanent) :: Nil))

      intercept[Exception] {
        dyingActor !! (Die, TimeoutMillis)
      }

      // give time for restart
      sleepFor(3 seconds)

      (dyingActor !! (Ping, TimeoutMillis)).getOrElse("nil") must be(PongMessage)

      inits.get must be(3)

      supervisor.shutdown()
    }
  }
}
