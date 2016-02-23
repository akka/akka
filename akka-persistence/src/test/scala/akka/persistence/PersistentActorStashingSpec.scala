/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence

import akka.actor.ActorRef
import akka.persistence.journal.SteppingInmemJournal
import akka.testkit.ImplicitSender
import com.typesafe.config.Config

import scala.concurrent.duration._

object PersistentActorStashingSpec {
  final case class Cmd(data: Any)
  final case class Evt(data: Any)

  abstract class ExamplePersistentActor(name: String) extends NamedPersistentActor(name) {
    var events: List[Any] = Nil
    var askedForDelete: Option[ActorRef] = None

    val updateState: Receive = {
      case Evt(data)               ⇒ events = data :: events
      case d @ Some(ref: ActorRef) ⇒ askedForDelete = d.asInstanceOf[Some[ActorRef]]
    }

    val commonBehavior: Receive = {
      case "boom"   ⇒ throw new TestException("boom")
      case GetState ⇒ sender() ! events.reverse
    }

    def receiveRecover = updateState
  }

  class UserStashPersistentActor(name: String) extends ExamplePersistentActor(name) {
    var stashed = false
    val receiveCommand: Receive = {
      case Cmd("a") ⇒ if (!stashed) { stash(); stashed = true } else sender() ! "a"
      case Cmd("b") ⇒ persist(Evt("b"))(evt ⇒ sender() ! evt.data)
      case Cmd("c") ⇒ unstashAll(); sender() ! "c"
    }
  }

  class UserStashManyPersistentActor(name: String) extends ExamplePersistentActor(name) {
    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd("a") ⇒ persist(Evt("a")) { evt ⇒
        updateState(evt)
        context.become(processC)
      }
      case Cmd("b-1") ⇒ persist(Evt("b-1"))(updateState)
      case Cmd("b-2") ⇒ persist(Evt("b-2"))(updateState)
    }

    val processC: Receive = {
      case Cmd("c") ⇒
        persist(Evt("c")) { evt ⇒
          updateState(evt)
          context.unbecome()
        }
        unstashAll()
      case other ⇒ stash()
    }
  }

  class UserStashFailurePersistentActor(name: String) extends ExamplePersistentActor(name) {
    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        if (data == "b-2") throw new TestException("boom")
        persist(Evt(data)) { event ⇒
          updateState(event)
          if (data == "a") context.become(otherCommandHandler)
        }
    }

    val otherCommandHandler: Receive = {
      case Cmd("c") ⇒
        persist(Evt("c")) { event ⇒
          updateState(event)
          context.unbecome()
        }
        unstashAll()
      case other ⇒ stash()
    }
  }

  class AsyncStashingPersistentActor(name: String) extends ExamplePersistentActor(name) {
    var stashed = false
    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd("a") ⇒ persistAsync(Evt("a"))(updateState)
      case Cmd("b") if !stashed ⇒
        stash(); stashed = true
      case Cmd("b") ⇒ persistAsync(Evt("b"))(updateState)
      case Cmd("c") ⇒ persistAsync(Evt("c"))(updateState); unstashAll()
    }
  }

}

abstract class PersistentActorStashingSpec(config: Config) extends PersistenceSpec(config)
  with ImplicitSender {

  import PersistentActorStashingSpec._

  "Stashing in a persistent actor" must {

    "support user stash operations" in {
      val persistentActor = namedPersistentActor[UserStashPersistentActor]
      persistentActor ! Cmd("a")
      persistentActor ! Cmd("b")
      persistentActor ! Cmd("c")
      expectMsg("b")
      expectMsg("c")
      expectMsg("a")
    }

    "support user stash operations with several stashed messages" in {
      val persistentActor = namedPersistentActor[UserStashManyPersistentActor]
      val n = 10
      val cmds = 1 to n flatMap (_ ⇒ List(Cmd("a"), Cmd("b-1"), Cmd("b-2"), Cmd("c")))
      val evts = 1 to n flatMap (_ ⇒ List("a", "c", "b-1", "b-2"))

      cmds foreach (persistentActor ! _)
      persistentActor ! GetState
      expectMsg(evts)
    }

    "support user stash operations under failures" in {
      val persistentActor = namedPersistentActor[UserStashFailurePersistentActor]
      val bs = 1 to 10 map ("b-" + _)
      persistentActor ! Cmd("a")
      bs foreach (persistentActor ! Cmd(_))
      persistentActor ! Cmd("c")
      persistentActor ! GetState
      expectMsg(List("a", "c") ++ bs.filter(_ != "b-2"))
    }
  }
}

class SteppingInMemPersistentActorStashingSpec extends PersistenceSpec(
  SteppingInmemJournal.config("persistence-stash").withFallback(PersistenceSpec.config("stepping-inmem", "SteppingInMemPersistentActorStashingSpec")))
  with ImplicitSender {

  import PersistentActorStashingSpec._

  "Stashing in a persistent actor mixed with persistAsync" should {

    "handle async callback not happening until next message has been stashed" in {
      val persistentActor = namedPersistentActor[AsyncStashingPersistentActor]
      awaitAssert(SteppingInmemJournal.getRef("persistence-stash"), 3.seconds)
      val journal = SteppingInmemJournal.getRef("persistence-stash")

      // initial read highest
      SteppingInmemJournal.step(journal)

      persistentActor ! Cmd("a")
      persistentActor ! Cmd("b")

      // allow the write to complete, after the stash
      SteppingInmemJournal.step(journal)

      persistentActor ! Cmd("c")
      // writing of c and b
      SteppingInmemJournal.step(journal)
      SteppingInmemJournal.step(journal)

      within(3.seconds) {
        awaitAssert {
          persistentActor ! GetState
          expectMsg(List("a", "c", "b"))
        }
      }
    }

  }

}

class LeveldbPersistentActorStashingSpec extends PersistentActorStashingSpec(PersistenceSpec.config("leveldb", "LeveldbPersistentActorStashingSpec"))
class InmemPersistentActorStashingSpec extends PersistentActorStashingSpec(PersistenceSpec.config("inmem", "InmemPersistentActorStashingSpec"))