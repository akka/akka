package akka.persistence

import akka.actor._
import akka.testkit._

object ProcessorSpec {
  val config =
    """
      |serialize-creators = on
      |serialize-messages = on
      |akka.persistence.journal.leveldb.dir = "target/journal-processor-spec"
    """.stripMargin

  case object GetState

  class RecoverTestProcessor extends Processor {
    var state = List.empty[String]
    def receive = {
      case "boom"                   ⇒ throw new Exception("boom")
      case Persistent("boom", _)    ⇒ throw new Exception("boom")
      case Persistent(payload, snr) ⇒ state = s"${payload}-${snr}" :: state
      case GetState                 ⇒ sender ! state.reverse
    }

    override def preRestartProcessor(reason: Throwable, message: Option[Any]) = {
      message match {
        case Some(m: Persistent) ⇒ delete(m) // delete message from journal
        case _                   ⇒ // ignore
      }
      super.preRestartProcessor(reason, message)
    }
  }

  class RecoverOffTestProcessor extends RecoverTestProcessor with TurnOffRecoverOnStart

  class StoredSenderTestProcessor extends Processor {
    def receive = {
      case Persistent(payload, _) ⇒ sender ! payload
    }
  }

  class RecoveryStatusTestProcessor extends Processor {
    def receive = {
      case Persistent("c", _) if !recoveryRunning    ⇒ sender ! "c"
      case Persistent(payload, _) if recoveryRunning ⇒ sender ! payload
    }
  }

  class BehaviorChangeTestProcessor extends Processor {
    val acceptA: Actor.Receive = {
      case Persistent("a", _) ⇒ {
        sender ! "a"
        context.become(acceptB)
      }
    }

    val acceptB: Actor.Receive = {
      case Persistent("b", _) ⇒ {
        sender ! "b"
        context.become(acceptA)
      }
    }

    def receive = acceptA
  }

  class FsmTestProcessor extends Processor with FSM[String, Int] {
    startWith("closed", 0)

    when("closed") {
      case Event(Persistent("a", _), counter) ⇒ {
        goto("open") using (counter + 1) replying (counter)
      }
    }

    when("open") {
      case Event(Persistent("b", _), counter) ⇒ {
        goto("closed") using (counter + 1) replying (counter)
      }
    }
  }

  class OutboundMessageTestProcessor extends Processor {
    def receive = {
      case Persistent(payload, snr) ⇒ sender ! Persistent(snr)
    }
  }

  class ResumeTestException extends Exception("test")

  class ResumeTestSupervisor extends Actor {
    val processor = context.actorOf(Props[ResumeTestProcessor], "processor")

    override val supervisorStrategy =
      OneForOneStrategy() {
        case _: ResumeTestException ⇒ SupervisorStrategy.Resume
      }

    def receive = {
      case m ⇒ processor forward m
    }
  }

  class ResumeTestProcessor extends Processor {
    var state: List[String] = Nil
    def receive = {
      case "boom"                   ⇒ throw new ResumeTestException
      case Persistent(payload, snr) ⇒ state = s"${payload}-${snr}" :: state
      case GetState                 ⇒ sender ! state.reverse
    }
  }

  class LastReplayedMsgFailsTestProcessor extends RecoverTestProcessor {
    override def preRestartProcessor(reason: Throwable, message: Option[Any]) = {
      message match {
        case Some(m: Persistent) ⇒ if (recoveryRunning) delete(m)
        case _                   ⇒
      }
      super.preRestartProcessor(reason, message)
    }
  }

  class AnyReplayedMsgFailsTestProcessor extends RecoverTestProcessor {
    val failOnReplayedA: Actor.Receive = {
      case Persistent("a", _) if recoveryRunning ⇒ throw new Exception("boom")
    }

    override def receive = failOnReplayedA orElse super.receive
  }
}

class ProcessorSpec extends AkkaSpec(ProcessorSpec.config) with PersistenceSpec with ImplicitSender {
  import ProcessorSpec._

  override protected def beforeEach() {
    super.beforeEach()

    val processor = system.actorOf(Props[RecoverTestProcessor], name)
    processor ! Persistent("a")
    processor ! Persistent("b")
    processor ! GetState
    expectMsg(List("a-1", "b-2"))
    stopAndAwaitTermination(processor)
  }

  "A processor" must {
    "recover state on explicit request" in {
      val processor = system.actorOf(Props[RecoverOffTestProcessor], name)
      processor ! Recover()
      processor ! GetState
      expectMsg(List("a-1", "b-2"))
    }
    "recover state automatically" in {
      val processor = system.actorOf(Props[RecoverTestProcessor], name)
      processor ! GetState
      expectMsg(List("a-1", "b-2"))
    }
    "recover state automatically on restart" in {
      val processor = system.actorOf(Props[RecoverTestProcessor], name)
      processor ! "boom"
      processor ! GetState
      expectMsg(List("a-1", "b-2"))
    }
    "buffer new messages until recovery completed" in {
      val processor = system.actorOf(Props[RecoverOffTestProcessor], name)
      processor ! Persistent("c")
      processor ! Recover()
      processor ! Persistent("d")
      processor ! GetState
      expectMsg(List("a-1", "b-2", "c-3", "d-4"))
    }
    "ignore redundant recovery requests" in {
      val processor = system.actorOf(Props[RecoverOffTestProcessor], name)
      processor ! Persistent("c")
      processor ! Recover()
      processor ! Persistent("d")
      processor ! Recover()
      processor ! Persistent("e")
      processor ! GetState
      expectMsg(List("a-1", "b-2", "c-3", "d-4", "e-5"))
    }
    "buffer new messages until restart-recovery completed" in {
      val processor = system.actorOf(Props[RecoverTestProcessor], name)
      processor ! "boom"
      processor ! Persistent("c")
      processor ! Persistent("d")
      processor ! GetState
      expectMsg(List("a-1", "b-2", "c-3", "d-4"))
    }
    "allow deletion of journaled messages on failure" in {
      val processor = system.actorOf(Props[RecoverTestProcessor], name)
      processor ! Persistent("boom") // journaled message causes failure and will be deleted
      processor ! GetState
      expectMsg(List("a-1", "b-2"))
    }
    "allow deletion of journaled messages on failure and buffer new messages until restart-recovery completed" in {
      val processor = system.actorOf(Props[RecoverTestProcessor], name)
      processor ! Persistent("boom") // journaled message causes failure and will be deleted
      processor ! Persistent("c")
      processor ! Persistent("d")
      processor ! GetState
      expectMsg(List("a-1", "b-2", "c-4", "d-5")) // deleted message leaves gap in sequence
    }
    "store sender references and restore them for replayed messages" in {
      system.actorOf(Props[StoredSenderTestProcessor], name)
      List("a", "b") foreach (expectMsg(_))
    }
    "properly indicate its recovery status" in {
      val processor = system.actorOf(Props[RecoveryStatusTestProcessor], name)
      processor ! Persistent("c")
      List("a", "b", "c") foreach (expectMsg(_))
    }
    "continue journaling when changing behavior" in {
      val processor = system.actorOf(Props[BehaviorChangeTestProcessor], name)
      processor ! Persistent("a")
      processor ! Persistent("b")
      List("a", "b", "a", "b") foreach (expectMsg(_))
    }
    "derive outbound messages from the current message" in {
      val processor = system.actorOf(Props[OutboundMessageTestProcessor], name)
      processor ! Persistent("c")
      1 to 3 foreach { _ ⇒ expectMsgPF() { case Persistent(payload, snr) ⇒ payload must be(snr) } }
    }
    "support recovery with upper sequence number bound" in {
      val processor = system.actorOf(Props[RecoverOffTestProcessor], name)
      processor ! Recover(1L)
      processor ! GetState
      expectMsg(List("a-1"))
    }
    "never replace journaled messages" in {
      val processor1 = system.actorOf(Props[RecoverOffTestProcessor], name)
      processor1 ! Recover(1L)
      processor1 ! Persistent("c")
      processor1 ! GetState
      expectMsg(List("a-1", "c-3"))
      stopAndAwaitTermination(processor1)

      val processor2 = system.actorOf(Props[RecoverOffTestProcessor], name)
      processor2 ! Recover()
      processor2 ! GetState
      expectMsg(List("a-1", "b-2", "c-3"))
    }
    "be able to skip restart recovery when being resumed" in {
      val supervisor1 = system.actorOf(Props[ResumeTestSupervisor], name)
      supervisor1 ! Persistent("a")
      supervisor1 ! Persistent("b")
      supervisor1 ! GetState
      expectMsg(List("a-1", "b-2"))
      stopAndAwaitTermination(supervisor1)

      val supervisor2 = system.actorOf(Props[ResumeTestSupervisor], name)
      supervisor2 ! Persistent("c")
      supervisor2 ! "boom"
      supervisor2 ! Persistent("d")
      supervisor2 ! GetState
      expectMsg(List("a-1", "b-2", "c-3", "d-4"))
      stopAndAwaitTermination(supervisor2)

      val supervisor3 = system.actorOf(Props[ResumeTestSupervisor], name)
      supervisor3 ! GetState
      expectMsg(List("a-1", "b-2", "c-3", "d-4"))
    }
    "be able to re-run restart recovery when it fails with last replayed message" in {
      val processor = system.actorOf(Props[LastReplayedMsgFailsTestProcessor], name)
      processor ! Persistent("c")
      processor ! Persistent("boom")
      processor ! Persistent("d")
      processor ! GetState
      expectMsg(List("a-1", "b-2", "c-3", "d-5"))
    }
    "be able to re-run initial recovery when it fails with a message that is not the last replayed message" in {
      val processor = system.actorOf(Props[AnyReplayedMsgFailsTestProcessor], name)
      processor ! Persistent("c")
      processor ! GetState
      expectMsg(List("b-2", "c-3"))
    }
    "be able to re-run restart recovery when it fails with a message that is not the last replayed message" in {
      val processor = system.actorOf(Props[AnyReplayedMsgFailsTestProcessor], "other") // new processor, no initial replay
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
  }

  "A processor" can {
    "be a finite state machine" in {
      val processor = system.actorOf(Props[FsmTestProcessor], name)
      processor ! Persistent("a")
      processor ! Persistent("b")
      List(0, 1, 2, 3) foreach (expectMsg(_))
    }
  }
}
