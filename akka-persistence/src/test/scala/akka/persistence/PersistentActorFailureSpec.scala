/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import akka.actor.{ OneForOneStrategy, _ }
import akka.persistence.journal.AsyncWriteTarget.{ ReplayFailure, ReplayMessages, ReplaySuccess, WriteMessages }
import akka.persistence.journal.inmem.InmemStore
import akka.persistence.journal.{ AsyncWriteJournal, AsyncWriteProxy }
import akka.testkit.{ EventFilter, ImplicitSender, TestEvent }
import akka.util.Timeout

import scala.collection.immutable
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NoStackTrace
import scala.util.{ Failure, Try }

object PersistentActorFailureSpec {
  import PersistentActorSpec.{ Cmd, Evt, ExamplePersistentActor }

  class SimulatedException(msg: String) extends RuntimeException(msg) with NoStackTrace
  class SimulatedSerializationException(msg: String) extends RuntimeException(msg) with NoStackTrace

  class FailingInmemJournal extends AsyncWriteProxy {
    import AsyncWriteProxy.SetStore

    val timeout = Timeout(3 seconds)

    override def preStart(): Unit = {
      super.preStart()
      self ! SetStore(context.actorOf(Props[FailingInmemStore]()))
    }

  }

  class FailingInmemStore extends InmemStore {
    def failingReceive: Receive = {
      case w: WriteMessages if isWrong(w) ⇒
        throw new SimulatedException("Simulated Store failure")
      case w: WriteMessages if checkSerializable(w).exists(_.isFailure) ⇒
        sender() ! checkSerializable(w)
      case ReplayMessages(pid, fromSnr, toSnr, max) ⇒
        val highest = highestSequenceNr(pid)
        val readFromStore = read(pid, fromSnr, toSnr, max)
        if (readFromStore.isEmpty)
          sender() ! ReplaySuccess(highest)
        else if (isCorrupt(readFromStore))
          sender() ! ReplayFailure(new SimulatedException(s"blahonga $fromSnr $toSnr"))
        else {
          readFromStore.foreach(sender() ! _)
          sender() ! ReplaySuccess(highest)
        }
    }

    def isWrong(w: WriteMessages): Boolean =
      w.messages.exists {
        case a: AtomicWrite ⇒
          a.payload.exists { case PersistentRepr(Evt(s: String), _) ⇒ s.contains("wrong") }
        case _ ⇒ false
      }

    def checkSerializable(w: WriteMessages): immutable.Seq[Try[Unit]] =
      w.messages.collect {
        case a: AtomicWrite ⇒
          a.payload.collectFirst {
            case PersistentRepr(Evt(s: String), _: Long) if s.contains("not serializable") ⇒ s
          } match {
            case Some(s) ⇒ Failure(new SimulatedSerializationException(s))
            case None    ⇒ AsyncWriteJournal.successUnit
          }
      }

    def isCorrupt(events: Seq[PersistentRepr]): Boolean =
      events.exists { case PersistentRepr(Evt(s: String), _) ⇒ s.contains("corrupt") }

    override def receive = failingReceive.orElse(super.receive)
  }

  class OnRecoveryFailurePersistentActor(name: String, probe: ActorRef) extends ExamplePersistentActor(name) {
    val receiveCommand: Receive = commonBehavior orElse {
      case c @ Cmd(txt) ⇒ persist(Evt(txt))(updateState)
    }

    override protected def onRecoveryFailure(cause: Throwable, event: Option[Any]): Unit =
      probe ! "recovery-failure:" + cause.getMessage
  }

  class Supervisor(testActor: ActorRef) extends Actor {
    override def supervisorStrategy = OneForOneStrategy(loggingEnabled = false) {
      case e ⇒
        testActor ! e
        SupervisorStrategy.Restart
    }

    def receive = {
      case props: Props ⇒ sender() ! context.actorOf(props)
      case m            ⇒ sender() ! m
    }
  }

  class ResumingSupervisor(testActor: ActorRef) extends Supervisor(testActor) {
    override def supervisorStrategy = OneForOneStrategy(loggingEnabled = false) {
      case e ⇒
        testActor ! e
        SupervisorStrategy.Resume
    }

  }

  class FailingRecovery(name: String, recoveryFailureProbe: Option[ActorRef]) extends ExamplePersistentActor(name) {
    def this(name: String) = this(name, None)

    override val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒ persist(Evt(s"${data}"))(updateState)
    }

    val failingRecover: Receive = {
      case Evt(data) if data == "bad" ⇒
        throw new SimulatedException("Simulated exception from receiveRecover")
    }

    override def receiveRecover: Receive = failingRecover.orElse[Any, Unit](super.receiveRecover)

  }

  class ThrowingActor1(name: String) extends ExamplePersistentActor(name) {
    override val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        persist(Evt(s"${data}"))(updateState)
        if (data == "err")
          throw new SimulatedException("Simulated exception 1")
    }
  }

  class ThrowingActor2(name: String) extends ExamplePersistentActor(name) {
    override val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        persist(Evt(s"${data}")) { evt ⇒
          if (data == "err")
            throw new SimulatedException("Simulated exception 1")
          updateState(evt)
        }

    }
  }
}

class PersistentActorFailureSpec extends PersistenceSpec(PersistenceSpec.config("inmem", "SnapshotFailureRobustnessSpec", extraConfig = Some(
  """
  akka.persistence.journal.inmem.class = "akka.persistence.PersistentActorFailureSpec$FailingInmemJournal"
  """))) with ImplicitSender {

  import PersistentActorFailureSpec._
  import PersistentActorSpec._

  system.eventStream.publish(TestEvent.Mute(
    EventFilter[akka.pattern.AskTimeoutException]()))

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
    "stop if recovery from persisted events fail" in {
      val persistentActor = namedPersistentActor[Behavior1PersistentActor]
      persistentActor ! Cmd("corrupt")
      persistentActor ! GetState
      expectMsg(List("corrupt-1", "corrupt-2"))

      // recover by creating another with same name
      system.actorOf(Props(classOf[Supervisor], testActor)) ! Props(classOf[Behavior1PersistentActor], name)
      val ref = expectMsgType[ActorRef]
      watch(ref)
      expectTerminated(ref)
    }
    "call onRecoveryFailure when recovery from persisted events fails" in {
      val props = Props(classOf[OnRecoveryFailurePersistentActor], name, testActor)

      val persistentActor = system.actorOf(props)
      persistentActor ! Cmd("corrupt")
      persistentActor ! GetState
      expectMsg(List("corrupt"))

      // recover by creating another with same name
      system.actorOf(Props(classOf[Supervisor], testActor)) ! props
      val ref = expectMsgType[ActorRef]
      expectMsg("recovery-failure:blahonga 1 1")
      watch(ref)
      expectTerminated(ref)
    }
    "call onPersistFailure and stop when persist fails" in {
      system.actorOf(Props(classOf[Supervisor], testActor)) ! Props(classOf[Behavior1PersistentActor], name)
      val persistentActor = expectMsgType[ActorRef]
      watch(persistentActor)
      persistentActor ! Cmd("wrong")
      expectMsg("Failure: wrong-1")
      expectTerminated(persistentActor)
    }
    "call onPersistFailure and stop if persistAsync fails" in {
      system.actorOf(Props(classOf[Supervisor], testActor)) ! Props(classOf[AsyncPersistPersistentActor], name)
      val persistentActor = expectMsgType[ActorRef]
      persistentActor ! Cmd("a")
      watch(persistentActor)
      expectMsg("a") // reply before persistAsync
      expectMsg("a-1") // reply after successful persistAsync
      persistentActor ! Cmd("wrong")
      expectMsg("wrong") // reply before persistAsync
      expectMsg("Failure: wrong-2") // onPersistFailure sent message
      expectTerminated(persistentActor)
    }
    "call onPersistRejected and continue if persist rejected" in {
      system.actorOf(Props(classOf[Supervisor], testActor)) ! Props(classOf[Behavior1PersistentActor], name)
      val persistentActor = expectMsgType[ActorRef]
      persistentActor ! Cmd("not serializable")
      expectMsg("Rejected: not serializable-1")
      expectMsg("Rejected: not serializable-2")

      persistentActor ! Cmd("a")
      persistentActor ! GetState
      expectMsg(List("a-1", "a-2"))
    }
    "stop if receiveRecover fails" in {
      prepareFailingRecovery()

      // recover by creating another with same name
      system.actorOf(Props(classOf[Supervisor], testActor)) ! Props(classOf[FailingRecovery], name)
      val ref = expectMsgType[ActorRef]
      watch(ref)
      expectTerminated(ref)
    }

    "support resume when persist followed by exception" in {
      system.actorOf(Props(classOf[ResumingSupervisor], testActor)) ! Props(classOf[ThrowingActor1], name)
      val persistentActor = expectMsgType[ActorRef]
      persistentActor ! Cmd("a")
      persistentActor ! Cmd("err")
      persistentActor ! Cmd("b")
      expectMsgType[SimulatedException] // from supervisor
      persistentActor ! Cmd("c")
      persistentActor ! GetState
      expectMsg(List("a", "err", "b", "c"))
    }

    "support restart when persist followed by exception" in {
      system.actorOf(Props(classOf[Supervisor], testActor)) ! Props(classOf[ThrowingActor1], name)
      val persistentActor = expectMsgType[ActorRef]
      persistentActor ! Cmd("a")
      persistentActor ! Cmd("err")
      persistentActor ! Cmd("b")
      expectMsgType[SimulatedException] // from supervisor
      persistentActor ! Cmd("c")
      persistentActor ! GetState
      expectMsg(List("a", "err", "b", "c"))
    }

    "support resume when persist handler throws exception" in {
      system.actorOf(Props(classOf[ResumingSupervisor], testActor)) ! Props(classOf[ThrowingActor2], name)
      val persistentActor = expectMsgType[ActorRef]
      persistentActor ! Cmd("a")
      persistentActor ! Cmd("b")
      persistentActor ! Cmd("err")
      persistentActor ! Cmd("c")
      expectMsgType[SimulatedException] // from supervisor
      persistentActor ! Cmd("d")
      persistentActor ! GetState
      expectMsg(List("a", "b", "c", "d"))
    }

    "support restart when persist handler throws exception" in {
      system.actorOf(Props(classOf[Supervisor], testActor)) ! Props(classOf[ThrowingActor2], name)
      val persistentActor = expectMsgType[ActorRef]
      persistentActor ! Cmd("a")
      persistentActor ! Cmd("b")
      persistentActor ! Cmd("err")
      persistentActor ! Cmd("c")
      expectMsgType[SimulatedException] // from supervisor
      persistentActor ! Cmd("d")
      persistentActor ! GetState
      // err was stored, and was be replayed
      expectMsg(List("a", "b", "err", "c", "d"))
    }

  }
}

