/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import akka.actor._
import akka.persistence.journal.AsyncWriteProxy
import akka.persistence.journal.inmem.InmemStore
import akka.testkit.{ ImplicitSender, AkkaSpec }
import akka.util.Timeout
import com.typesafe.config.Config
import scala.concurrent.duration._
import akka.persistence.journal.AsyncWriteTarget.{ ReplayFailure, ReplaySuccess, ReplayMessages, WriteMessages }
import scala.language.postfixOps
import scala.Some
import akka.actor.OneForOneStrategy
import scala.util.control.NoStackTrace
import akka.testkit.TestProbe

object PersistentActorFailureSpec {
  import PersistentActorSpec.Cmd
  import PersistentActorSpec.Evt
  import PersistentActorSpec.ExamplePersistentActor

  class FailingInmemJournal extends AsyncWriteProxy {
    import AsyncWriteProxy.SetStore

    val timeout = Timeout(3 seconds)

    override def preStart(): Unit = {
      super.preStart()
      self ! SetStore(context.actorOf(Props[FailingInmemStore]))
    }

  }

  class FailingInmemStore extends InmemStore {
    def failingReceive: Receive = {
      case w: WriteMessages if isWrong(w) ⇒
        throw new RuntimeException("Simulated Store failure") with NoStackTrace
      case ReplayMessages(pid, fromSnr, toSnr, max) ⇒
        val readFromStore = read(pid, fromSnr, toSnr, max)
        if (readFromStore.length == 0)
          sender() ! ReplaySuccess
        else if (isCorrupt(readFromStore))
          sender() ! ReplayFailure(new IllegalArgumentException(s"blahonga $fromSnr $toSnr"))
        else {
          readFromStore.foreach(sender() ! _)
          sender() ! ReplaySuccess
        }
    }

    def isWrong(w: WriteMessages): Boolean =
      w.messages.exists {
        case PersistentRepr(Evt(s: String), _) ⇒ s.contains("wrong")
        case x                                 ⇒ false
      }

    def isCorrupt(events: Seq[PersistentRepr]): Boolean =
      events.exists { case PersistentRepr(Evt(s: String), _) ⇒ s.contains("corrupt") }

    override def receive = failingReceive.orElse(super.receive)
  }

  class Supervisor(testActor: ActorRef) extends Actor {
    override def supervisorStrategy = OneForOneStrategy(loggingEnabled = false) {
      case e: ActorKilledException ⇒
        testActor ! e
        SupervisorStrategy.Stop
      case e ⇒
        testActor ! e
        SupervisorStrategy.Restart
    }

    def receive = {
      case props: Props ⇒ sender() ! context.actorOf(props)
      case m            ⇒ sender() ! m
    }
  }

  class FailingRecovery(name: String, recoveryFailureProbe: Option[ActorRef]) extends ExamplePersistentActor(name) {
    def this(name: String) = this(name, None)

    override val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒ persist(Evt(s"${data}"))(updateState)
    }

    val failingRecover: Receive = {
      case Evt(data) if data == "bad" ⇒
        throw new RuntimeException("Simulated exception from receiveRecover")

      case r @ RecoveryFailure(cause) if recoveryFailureProbe.isDefined ⇒
        recoveryFailureProbe.foreach { _ ! r }
        throw new ActorKilledException(cause.getMessage)
    }

    override def receiveRecover: Receive = failingRecover orElse super.receiveRecover

    override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
      super.preRestart(reason, message)
    }
  }
}

class PersistentActorFailureSpec extends AkkaSpec(PersistenceSpec.config("inmem", "SnapshotFailureRobustnessSpec", extraConfig = Some(
  """
  akka.persistence.journal.inmem.class = "akka.persistence.PersistentActorFailureSpec$FailingInmemJournal"
  """))) with PersistenceSpec with ImplicitSender {

  import PersistentActorFailureSpec._
  import PersistentActorSpec._

  def prepareFailingRecovery(): Unit = {
    val persistentActor = namedPersistentActor[FailingRecovery]
    persistentActor ! Cmd("a")
    persistentActor ! Cmd("b")
    persistentActor ! Cmd("bad")
    persistentActor ! Cmd("c")
    persistentActor ! GetState
    expectMsg(List("a", "b", "bad", "c"))
  }

  "A persistent actor" must {
    "throw ActorKilledException if recovery from persisted events fail" in {
      val persistentActor = namedPersistentActor[Behavior1PersistentActor]
      persistentActor ! Cmd("corrupt")
      persistentActor ! GetState
      expectMsg(List("corrupt-1", "corrupt-2"))

      // recover by creating another with same name
      system.actorOf(Props(classOf[Supervisor], testActor)) ! Props(classOf[Behavior1PersistentActor], name)
      expectMsgType[ActorRef]
      expectMsgType[ActorKilledException]
    }
    "throw ActorKilledException if persist fails" in {
      system.actorOf(Props(classOf[Supervisor], testActor)) ! Props(classOf[Behavior1PersistentActor], name)
      val persistentActor = expectMsgType[ActorRef]
      persistentActor ! Cmd("wrong")
      expectMsgType[ActorKilledException]
    }
    "throw ActorKilledException if persistAsync fails" in {
      system.actorOf(Props(classOf[Supervisor], testActor)) ! Props(classOf[AsyncPersistPersistentActor], name)
      val persistentActor = expectMsgType[ActorRef]
      persistentActor ! Cmd("a")
      expectMsg("a") // reply before persistAsync
      expectMsg("a-1") // reply after successful persistAsync
      persistentActor ! Cmd("wrong")
      expectMsg("wrong") // reply before persistAsync
      expectMsgType[ActorKilledException]
    }
    "throw ActorKilledException if receiveRecover fails" in {
      prepareFailingRecovery()

      // recover by creating another with same name
      system.actorOf(Props(classOf[Supervisor], testActor)) ! Props(classOf[FailingRecovery], name)
      expectMsgType[ActorRef]
      expectMsgType[ActorKilledException]
    }
    "include failing event in RecoveryFailure message" in {
      prepareFailingRecovery()

      // recover by creating another with same name
      val probe = TestProbe()
      system.actorOf(Props(classOf[Supervisor], testActor)) ! Props(classOf[FailingRecovery], name, Some(probe.ref))
      expectMsgType[ActorRef]
      expectMsgType[ActorKilledException]
      val recoveryFailure = probe.expectMsgType[RecoveryFailure]
      recoveryFailure.payload should be(Some(Evt("bad")))
      recoveryFailure.sequenceNr should be(Some(3L))
    }
  }
}

