/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import akka.actor._
import akka.testkit._
import com.typesafe.config._
import scala.concurrent.duration._
import scala.collection.immutable.Seq
import scala.util.control.NoStackTrace

object ProcessorSpec {
  class RecoverTestProcessor(name: String) extends NamedProcessor(name) {
    var state = List.empty[String]
    def receive = {
      case "boom"                   ⇒ throw new TestException("boom")
      case Persistent("boom", _)    ⇒ throw new TestException("boom")
      case Persistent(payload, snr) ⇒ state = s"${payload}-${snr}" :: state
      case GetState                 ⇒ sender() ! state.reverse
    }

    override def preRestart(reason: Throwable, message: Option[Any]) = {
      message match {
        case Some(m: Persistent) ⇒ deleteMessage(m.sequenceNr) // delete message from journal
        case _                   ⇒ // ignore
      }
      super.preRestart(reason, message)
    }
  }

  class RecoverOffTestProcessor(name: String) extends RecoverTestProcessor(name) with TurnOffRecoverOnStart

  class StoredSenderTestProcessor(name: String) extends NamedProcessor(name) {
    def receive = {
      case Persistent(payload, _) ⇒ sender() ! payload
    }
  }

  class RecoveryStatusTestProcessor(name: String) extends NamedProcessor(name) {
    def receive = {
      case Persistent("c", _) if !recoveryRunning    ⇒ sender() ! "c"
      case Persistent(payload, _) if recoveryRunning ⇒ sender() ! payload
    }
  }

  class BehaviorChangeTestProcessor(name: String) extends NamedProcessor(name) {
    val acceptA: Actor.Receive = {
      case Persistent("a", _) ⇒
        sender() ! "a"
        context.become(acceptB)
    }

    val acceptB: Actor.Receive = {
      case Persistent("b", _) ⇒
        sender() ! "b"
        context.become(acceptA)
    }

    def receive = acceptA
  }

  class FsmTestProcessor(name: String) extends NamedProcessor(name) with FSM[String, Int] {
    startWith("closed", 0)

    when("closed") {
      case Event(Persistent("a", _), counter) ⇒
        goto("open") using (counter + 1) replying (counter)
    }

    when("open") {
      case Event(Persistent("b", _), counter) ⇒
        goto("closed") using (counter + 1) replying (counter)
    }
  }

  class OutboundMessageTestProcessor(name: String) extends NamedProcessor(name) {
    def receive = {
      case Persistent(payload, snr) ⇒ sender() ! Persistent(snr)
    }
  }

  class ResumeTestException extends TestException("test")

  class ResumeTestSupervisor(name: String) extends Actor {
    val processor = context.actorOf(Props(classOf[ResumeTestProcessor], name))

    override val supervisorStrategy =
      OneForOneStrategy() {
        case _: ResumeTestException ⇒ SupervisorStrategy.Resume
      }

    def receive = {
      case m ⇒ processor forward m
    }
  }

  class ResumeTestProcessor(name: String) extends NamedProcessor(name) {
    var state: List[String] = Nil
    def receive = {
      case "boom"                   ⇒ throw new ResumeTestException
      case Persistent(payload, snr) ⇒ state = s"${payload}-${snr}" :: state
      case GetState                 ⇒ sender() ! state.reverse
    }
  }

  class LastReplayedMsgFailsTestProcessor(name: String) extends RecoverTestProcessor(name) {
    override def preRestart(reason: Throwable, message: Option[Any]) = {
      message match {
        case Some(m: Persistent) ⇒ if (recoveryRunning) deleteMessage(m.sequenceNr)
        case _                   ⇒
      }
      super.preRestart(reason, message)
    }
  }

  class AnyReplayedMsgFailsTestProcessor(name: String) extends RecoverTestProcessor(name) {
    val failOnReplayedA: Actor.Receive = {
      case Persistent("a", _) if recoveryRunning ⇒ throw new TestException("boom")
    }

    override def receive = failOnReplayedA orElse super.receive
  }

  final case class Delete1(snr: Long)
  final case class DeleteN(toSnr: Long)

  class DeleteMessageTestProcessor(name: String) extends RecoverTestProcessor(name) {
    override def receive = deleteReceive orElse super.receive

    def deleteReceive: Actor.Receive = {
      case Delete1(snr)   ⇒ deleteMessage(snr)
      case DeleteN(toSnr) ⇒ deleteMessages(toSnr)
    }
  }

  class StackableTestProcessor(val probe: ActorRef) extends StackableTestProcessor.BaseActor with Processor with StackableTestProcessor.MixinActor {
    override def persistenceId: String = "StackableTestPersistentActor"

    def receive = {
      case "restart" ⇒ throw new Exception("triggering restart") with NoStackTrace { override def toString = "Boom!" }
    }

    override def preStart(): Unit = {
      probe ! "preStart"
      super.preStart()
    }

    override def postStop(): Unit = {
      probe ! "postStop"
      super.postStop()
    }

    override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
      probe ! "preRestart"
      super.preRestart(reason, message)
    }

    override def postRestart(reason: Throwable): Unit = {
      probe ! "postRestart"
      super.postRestart(reason)
    }
  }

  object StackableTestProcessor {
    trait BaseActor extends Actor { this: StackableTestProcessor ⇒
      override protected[akka] def aroundPreStart() = {
        probe ! "base aroundPreStart"
        super.aroundPreStart()
      }

      override protected[akka] def aroundPostStop() = {
        probe ! "base aroundPostStop"
        super.aroundPostStop()
      }

      override protected[akka] def aroundPreRestart(reason: Throwable, message: Option[Any]) = {
        probe ! "base aroundPreRestart"
        super.aroundPreRestart(reason, message)
      }

      override protected[akka] def aroundPostRestart(reason: Throwable) = {
        probe ! "base aroundPostRestart"
        super.aroundPostRestart(reason)
      }

      override protected[akka] def aroundReceive(receive: Receive, message: Any) = {
        probe ! "base aroundReceive"
        super.aroundReceive(receive, message)
      }
    }

    trait MixinActor extends Actor { this: StackableTestProcessor ⇒
      override protected[akka] def aroundPreStart() = {
        probe ! "mixin aroundPreStart"
        super.aroundPreStart()
      }

      override protected[akka] def aroundPostStop() = {
        probe ! "mixin aroundPostStop"
        super.aroundPostStop()
      }

      override protected[akka] def aroundPreRestart(reason: Throwable, message: Option[Any]) = {
        probe ! "mixin aroundPreRestart"
        super.aroundPreRestart(reason, message)
      }

      override protected[akka] def aroundPostRestart(reason: Throwable) = {
        probe ! "mixin aroundPostRestart"
        super.aroundPostRestart(reason)
      }

      override protected[akka] def aroundReceive(receive: Receive, message: Any) = {
        if (message == "restart" && recoveryFinished) { probe ! "mixin aroundReceive" }
        super.aroundReceive(receive, message)
      }
    }
  }
}

abstract class ProcessorSpec(config: Config) extends AkkaSpec(config) with PersistenceSpec with ImplicitSender {
  import JournalProtocol._
  import ProcessorSpec._

  override protected def beforeEach() {
    super.beforeEach()

    val processor = namedProcessor[RecoverTestProcessor]
    processor ! Persistent("a")
    processor ! Persistent("b")
    processor ! GetState
    expectMsg(List("a-1", "b-2"))
  }

  "A processor" must {
    "recover state on explicit request" in {
      val processor = namedProcessor[RecoverOffTestProcessor]
      processor ! Recover()
      processor ! GetState
      expectMsg(List("a-1", "b-2"))
    }
    "recover state automatically" in {
      val processor = namedProcessor[RecoverTestProcessor]
      processor ! GetState
      expectMsg(List("a-1", "b-2"))
    }
    "recover state automatically on restart" in {
      val processor = namedProcessor[RecoverTestProcessor]
      processor ! "boom"
      processor ! GetState
      expectMsg(List("a-1", "b-2"))
    }
    "buffer new messages until recovery completed" in {
      val processor = namedProcessor[RecoverOffTestProcessor]
      processor ! Persistent("c")
      processor ! Recover()
      processor ! Persistent("d")
      processor ! GetState
      expectMsg(List("a-1", "b-2", "c-3", "d-4"))
    }
    "ignore redundant recovery requests" in {
      val processor = namedProcessor[RecoverOffTestProcessor]
      processor ! Persistent("c")
      processor ! Recover()
      processor ! Persistent("d")
      processor ! Recover()
      processor ! Persistent("e")
      processor ! GetState
      expectMsg(List("a-1", "b-2", "c-3", "d-4", "e-5"))
    }
    "buffer new messages until restart-recovery completed" in {
      val processor = namedProcessor[RecoverTestProcessor]
      processor ! "boom"
      processor ! Persistent("c")
      processor ! Persistent("d")
      processor ! GetState
      expectMsg(List("a-1", "b-2", "c-3", "d-4"))
    }
    "allow deletion of journaled messages on failure" in {
      val processor = namedProcessor[RecoverTestProcessor]
      processor ! Persistent("boom") // journaled message causes failure and will be deleted
      processor ! GetState
      expectMsg(List("a-1", "b-2"))
    }
    "allow deletion of journaled messages on failure and buffer new messages until restart-recovery completed" in {
      val processor = namedProcessor[RecoverTestProcessor]
      processor ! Persistent("boom") // journaled message causes failure and will be deleted
      processor ! Persistent("c")
      processor ! Persistent("d")
      processor ! GetState
      expectMsg(List("a-1", "b-2", "c-4", "d-5")) // deleted message leaves gap in sequence
    }
    "store sender references and restore them for replayed messages" in {
      namedProcessor[StoredSenderTestProcessor]
      List("a", "b") foreach (expectMsg(_))
    }
    "properly indicate its recovery status" in {
      val processor = namedProcessor[RecoveryStatusTestProcessor]
      processor ! Persistent("c")
      List("a", "b", "c") foreach (expectMsg(_))
    }
    "continue journaling when changing behavior" in {
      val processor = namedProcessor[BehaviorChangeTestProcessor]
      processor ! Persistent("a")
      processor ! Persistent("b")
      List("a", "b", "a", "b") foreach (expectMsg(_))
    }
    "derive outbound messages from the current message" in {
      val processor = namedProcessor[OutboundMessageTestProcessor]
      processor ! Persistent("c")
      1 to 3 foreach { _ ⇒ expectMsgPF() { case Persistent(payload, snr) ⇒ payload should be(snr) } }
    }
    "support recovery with upper sequence number bound" in {
      val processor = namedProcessor[RecoverOffTestProcessor]
      processor ! Recover(toSequenceNr = 1L)
      processor ! GetState
      expectMsg(List("a-1"))
    }
    "never replace journaled messages" in {
      val processor1 = namedProcessor[RecoverOffTestProcessor]
      processor1 ! Recover(toSequenceNr = 1L)
      processor1 ! Persistent("c")
      processor1 ! GetState
      expectMsg(List("a-1", "c-3"))

      val processor2 = namedProcessor[RecoverOffTestProcessor]
      processor2 ! Recover()
      processor2 ! GetState
      expectMsg(List("a-1", "b-2", "c-3"))
    }
    "be able to skip restart recovery when being resumed" in {
      val supervisor1 = system.actorOf(Props(classOf[ResumeTestSupervisor], "processor"))
      supervisor1 ! Persistent("a")
      supervisor1 ! Persistent("b")
      supervisor1 ! GetState
      expectMsg(List("a-1", "b-2"))

      val supervisor2 = system.actorOf(Props(classOf[ResumeTestSupervisor], "processor"))
      supervisor2 ! Persistent("c")
      supervisor2 ! "boom"
      supervisor2 ! Persistent("d")
      supervisor2 ! GetState
      expectMsg(List("a-1", "b-2", "c-3", "d-4"))

      val supervisor3 = system.actorOf(Props(classOf[ResumeTestSupervisor], "processor"))
      supervisor3 ! GetState
      expectMsg(List("a-1", "b-2", "c-3", "d-4"))
    }
    "be able to re-run restart recovery when it fails with last replayed message" in {
      val processor = namedProcessor[LastReplayedMsgFailsTestProcessor]
      processor ! Persistent("c")
      processor ! Persistent("boom")
      processor ! Persistent("d")
      processor ! GetState
      expectMsg(List("a-1", "b-2", "c-3", "d-5"))
    }
    "be able to re-run initial recovery when it fails with a message that is not the last replayed message" in {
      val processor = namedProcessor[AnyReplayedMsgFailsTestProcessor]
      processor ! Persistent("c")
      processor ! GetState
      expectMsg(List("b-2", "c-3"))
    }
    "be able to re-run restart recovery when it fails with a message that is not the last replayed message" in {
      val processor = system.actorOf(Props(classOf[AnyReplayedMsgFailsTestProcessor], "other")) // new processor, no initial replay
      processor ! Persistent("b")
      processor ! Persistent("a")
      processor ! Persistent("c")
      processor ! Persistent("d")
      processor ! Persistent("e")
      processor ! Persistent("f")
      processor ! Persistent("g")
      processor ! Persistent("h")
      processor ! Persistent("i")
      processor ! "boom"
      processor ! Persistent("j")
      processor ! GetState
      expectMsg(List("b-1", "c-3", "d-4", "e-5", "f-6", "g-7", "h-8", "i-9", "j-10"))
    }
    "support batch writes" in {
      val processor = namedProcessor[RecoverTestProcessor]
      processor ! PersistentBatch(Seq(Persistent("c"), Persistent("d"), Persistent("e")))
      processor ! Persistent("f")
      processor ! GetState
      expectMsg(List("a-1", "b-2", "c-3", "d-4", "e-5", "f-6"))
    }
    "support single message deletions" in {
      val deleteProbe = TestProbe()

      system.eventStream.subscribe(deleteProbe.ref, classOf[DeleteMessages])

      val processor1 = namedProcessor[DeleteMessageTestProcessor]
      processor1 ! Persistent("c")
      processor1 ! Persistent("d")
      processor1 ! Persistent("e")
      processor1 ! Delete1(4)
      deleteProbe.expectMsgType[DeleteMessages]

      val processor2 = namedProcessor[DeleteMessageTestProcessor]
      processor2 ! GetState

      expectMsg(List("a-1", "b-2", "c-3", "e-5"))
    }
    "support bulk message deletions" in {
      val deleteProbe = TestProbe()

      system.eventStream.subscribe(deleteProbe.ref, classOf[DeleteMessagesTo])

      val processor1 = namedProcessor[DeleteMessageTestProcessor]
      processor1 ! Persistent("c")
      processor1 ! Persistent("d")
      processor1 ! Persistent("e")
      processor1 ! DeleteN(4)
      deleteProbe.expectMsgType[DeleteMessagesTo]

      val processor2 = namedProcessor[DeleteMessageTestProcessor]
      processor2 ! GetState

      expectMsg(List("e-5"))

      processor2 ! Persistent("f")
      processor2 ! Persistent("g")
      processor2 ! DeleteN(6)
      deleteProbe.expectMsgType[DeleteMessagesTo]

      val processor3 = namedProcessor[DeleteMessageTestProcessor]
      processor3 ! GetState

      expectMsg(List("g-7"))
    }
  }

  "A processor" can {
    "be a finite state machine" in {
      val processor = namedProcessor[FsmTestProcessor]
      processor ! Persistent("a")
      processor ! Persistent("b")
      List(0, 1, 2, 3) foreach (expectMsg(_))
    }

    "be used as a stackable modification" in {
      val processor = system.actorOf(Props(classOf[StackableTestProcessor], testActor))
      expectMsg("mixin aroundPreStart")
      expectMsg("base aroundPreStart")
      expectMsg("preStart")

      processor ! "restart"
      expectMsg("mixin aroundReceive")
      expectMsg("base aroundReceive")

      expectMsg("mixin aroundPreRestart")
      expectMsg("base aroundPreRestart")
      expectMsg("preRestart")
      expectMsg("postStop")

      expectMsg("mixin aroundPostRestart")
      expectMsg("base aroundPostRestart")
      expectMsg("postRestart")
      expectMsg("preStart")

      processor ! PoisonPill
      expectMsg("mixin aroundPostStop")
      expectMsg("base aroundPostStop")
      expectMsg("postStop")

      expectNoMsg(100.millis)
    }
  }
}

class LeveldbProcessorSpec extends ProcessorSpec(PersistenceSpec.config("leveldb", "LeveldbProcessorSpec"))
class InmemProcessorSpec extends ProcessorSpec(PersistenceSpec.config("inmem", "InmemProcessorSpec"))
