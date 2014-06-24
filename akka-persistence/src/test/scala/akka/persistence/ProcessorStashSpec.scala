/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import com.typesafe.config._

import akka.actor._
import akka.testkit._

object ProcessorStashSpec {
  class StashingProcessor(name: String) extends NamedProcessor(name) {
    var state: List[String] = Nil

    val behaviorA: Actor.Receive = {
      case Persistent("a", snr) ⇒
        update("a", snr)
        context.become(behaviorB)
      case Persistent("b", snr) ⇒
        update("b", snr)
      case Persistent("c", snr) ⇒
        update("c", snr)
        unstashAll()
      case "x" ⇒
        update("x")
      case "boom"                ⇒ throw new TestException("boom")
      case Persistent("boom", _) ⇒ throw new TestException("boom")
      case GetState              ⇒ sender() ! state.reverse
    }

    val behaviorB: Actor.Receive = {
      case Persistent("b", _) ⇒
        stash()
        context.become(behaviorA)
      case "x" ⇒
        stash()
    }

    def receive = behaviorA

    def update(payload: String, snr: Long = 0L) {
      state = s"${payload}-${snr}" :: state
    }
  }

  class RecoveryFailureStashingProcessor(name: String) extends StashingProcessor(name) {
    override def preRestart(reason: Throwable, message: Option[Any]) = {
      message match {
        case Some(m: Persistent) ⇒ if (recoveryRunning) deleteMessage(m.sequenceNr)
        case _                   ⇒
      }
      super.preRestart(reason, message)
    }
  }
}

abstract class ProcessorStashSpec(config: Config) extends AkkaSpec(config) with PersistenceSpec with ImplicitSender {
  import ProcessorStashSpec._

  "A processor" must {
    "support user stash and unstash operations for persistent messages" in {
      val p1 = namedProcessor[StashingProcessor]
      p1 ! Persistent("a")
      p1 ! Persistent("b")
      p1 ! Persistent("c")
      p1 ! GetState
      expectMsg(List("a-1", "c-3", "b-2"))

      val p2 = namedProcessor[StashingProcessor]
      p2 ! Persistent("a")
      p2 ! Persistent("b")
      p2 ! Persistent("c")
      p2 ! GetState
      expectMsg(List("a-1", "c-3", "b-2", "a-4", "c-6", "b-5"))
    }
    "support user stash and unstash operations for persistent and transient messages" in {
      val p1 = namedProcessor[StashingProcessor]
      p1 ! Persistent("a")
      p1 ! "x"
      p1 ! Persistent("b")
      p1 ! Persistent("c")
      p1 ! GetState
      expectMsg(List("a-1", "c-3", "x-0", "b-2"))

      val p2 = namedProcessor[StashingProcessor]
      p2 ! Persistent("a")
      p2 ! "x"
      p2 ! Persistent("b")
      p2 ! Persistent("c")
      p2 ! GetState
      expectMsg(List("a-1", "c-3", "b-2", "a-4", "c-6", "x-0", "b-5"))
    }
    "support restarts between user stash and unstash operations" in {
      val p1 = namedProcessor[StashingProcessor]
      p1 ! Persistent("a")
      p1 ! Persistent("b")
      p1 ! "boom"
      p1 ! Persistent("c")
      p1 ! GetState
      expectMsg(List("a-1", "c-3", "b-2"))

      val p2 = namedProcessor[StashingProcessor]
      p2 ! Persistent("a")
      p2 ! Persistent("b")
      p2 ! "boom"
      p2 ! Persistent("c")
      p2 ! GetState
      expectMsg(List("a-1", "c-3", "b-2", "a-4", "c-6", "b-5"))
    }
    "support multiple restarts between user stash and unstash operations" in {
      val p1 = namedProcessor[RecoveryFailureStashingProcessor]
      p1 ! Persistent("a")
      p1 ! Persistent("b")
      p1 ! Persistent("boom")
      p1 ! Persistent("c")
      p1 ! GetState
      expectMsg(List("a-1", "c-4", "b-2"))

      val p2 = namedProcessor[RecoveryFailureStashingProcessor]
      p2 ! Persistent("a")
      p2 ! Persistent("b")
      p2 ! Persistent("boom")
      p2 ! Persistent("c")
      p2 ! GetState
      expectMsg(List("a-1", "c-4", "b-2", "a-5", "c-8", "b-6"))
    }
  }
}

class LeveldbProcessorStashSpec extends ProcessorStashSpec(PersistenceSpec.config("leveldb", "LeveldbProcessorStashSpec"))
class InmemProcessorStashSpec extends ProcessorStashSpec(PersistenceSpec.config("inmem", "InmemProcessorStashSpec"))
