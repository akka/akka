/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence

import akka.actor.ActorRef
import akka.persistence.journal.SteppingInmemJournal
import akka.testkit.ImplicitSender
import com.typesafe.config.Config

import scala.concurrent.duration._
import scala.reflect.ClassTag

object PersistentActorStashingSpec {
  final case class Cmd(data: Any)
  final case class Evt(data: Any)

  abstract class StashExamplePersistentActor(name: String) extends NamedPersistentActor(name) {
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
    
    def unstashBehavior: Receive

    def receiveRecover = updateState
  }

  class UserStashPersistentActor(name: String) extends StashExamplePersistentActor(name) {
    var stashed = false
    
    val receiveCommand: Receive = unstashBehavior orElse {
      case Cmd("a") if !stashed ⇒ stash(); stashed = true
      case Cmd("a")             ⇒ sender() ! "a"
      case Cmd("b")             ⇒ persist(Evt("b"))(evt ⇒ sender() ! evt.data)
    }
    
    def unstashBehavior: Receive = {
      case Cmd("c") ⇒ unstashAll(); sender () ! "c"
    }
  }
  
  class UserStashWithinHandlerPersistentActor(name: String) extends UserStashPersistentActor(name: String) {
    override def unstashBehavior: Receive = {
      case Cmd("c") ⇒  persist(Evt("c")) { evt ⇒ sender() ! evt.data; unstashAll() }
    }
  }

  class UserStashManyPersistentActor(name: String) extends StashExamplePersistentActor(name) {
    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd("a")   ⇒ persist(Evt("a")) { evt ⇒
        updateState(evt)
        context.become(processC)
      }
      case Cmd("b-1") ⇒ persist(Evt("b-1"))(updateState)
      case Cmd("b-2") ⇒ persist(Evt("b-2"))(updateState)
    }

    val processC: Receive = unstashBehavior orElse {
      case other ⇒ stash()
    }
    
    def unstashBehavior: Receive = {
      case Cmd("c") ⇒
        persist(Evt("c")) { evt ⇒ updateState(evt); context.unbecome() }
        unstashAll()
    }
  }
  
  class UserStashWithinHandlerManyPersistentActor(name: String) extends UserStashManyPersistentActor(name) {
    override def unstashBehavior: Receive = {
      case Cmd("c") ⇒ persist(Evt("c")) { evt ⇒ updateState(evt); context.unbecome(); unstashAll() }
    }
  }

  class UserStashFailurePersistentActor(name: String) extends StashExamplePersistentActor(name) {
    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        if (data == "b-2") throw new TestException("boom")
        persist(Evt(data)) { evt ⇒
          updateState(evt)
          if (data == "a") context.become(otherCommandHandler)
        }
    }

    val otherCommandHandler: Receive = unstashBehavior orElse {
      case other ⇒ stash()
    }
    
    def unstashBehavior: Receive = {
      case Cmd("c") ⇒
        persist(Evt("c")) { evt ⇒
          updateState(evt)
          context.unbecome()
        }
        unstashAll()
    }
  }

  class UserStashWithinHandlerFailureCallbackPersistentActor(name: String) extends UserStashFailurePersistentActor(name) {
    override def unstashBehavior: Receive = {
      case Cmd("c") ⇒
        persist(Evt("c")) { evt ⇒
          updateState(evt)
          context.unbecome()
          unstashAll()
        }
    }
  }

  class AsyncStashingPersistentActor(name: String) extends StashExamplePersistentActor(name) {
    var stashed = false
    
    val receiveCommand: Receive = commonBehavior orElse unstashBehavior orElse {
      case Cmd("a")             ⇒ persistAsync(Evt("a"))(updateState)
      case Cmd("b") if !stashed ⇒ stash(); stashed = true
      case Cmd("b")             ⇒ persistAsync(Evt("b"))(updateState)
    }

    override def unstashBehavior: Receive = {
      case Cmd("c") ⇒ persistAsync(Evt("c"))(updateState); unstashAll()
    }
  }
  
  class AsyncStashingWithinHandlerPersistentActor(name: String) extends AsyncStashingPersistentActor(name) {
    override def unstashBehavior: Receive = {
      case Cmd("c") ⇒ persistAsync(Evt("c")) { evt ⇒ updateState(evt); unstashAll() }
    }
  }

}

abstract class PersistentActorStashingSpec(config: Config) extends PersistenceSpec(config)
  with ImplicitSender {
  import PersistentActorStashingSpec._

  def stash[T <: NamedPersistentActor : ClassTag](): Unit = {
    "support user stash operations" in {
      val persistentActor = namedPersistentActor[T]
      persistentActor ! Cmd("a")
      persistentActor ! Cmd("b")
      persistentActor ! Cmd("c")
      expectMsg("b")
      expectMsg("c")
      expectMsg("a")
    }
  }

  def stashWithSeveralMessages[T <: NamedPersistentActor : ClassTag](): Unit = {
    "support user stash operations with several stashed messages" in {
      val persistentActor = namedPersistentActor[T]
      val n = 10
      val cmds = 1 to n flatMap (_ ⇒ List(Cmd("a"), Cmd("b-1"), Cmd("b-2"), Cmd("c")))
      val evts = 1 to n flatMap (_ ⇒ List("a", "c", "b-1", "b-2"))

      cmds foreach (persistentActor ! _)
      persistentActor ! GetState
      expectMsg(evts)
    }
  }

  def stashUnderFailures[T <: NamedPersistentActor : ClassTag](): Unit = {
    "support user stash operations under failures" in {
      val persistentActor = namedPersistentActor[T]
      val bs = 1 to 10 map ("b-" + _)
      persistentActor ! Cmd("a")
      bs foreach (persistentActor ! Cmd(_))
      persistentActor ! Cmd("c")
      persistentActor ! GetState
      expectMsg(List("a", "c") ++ bs.filter(_ != "b-2"))
    }
  }

  "Stashing in a persistent actor" must {
    behave like stash[UserStashPersistentActor]()
    behave like stashWithSeveralMessages[UserStashManyPersistentActor]()
    behave like stashUnderFailures[UserStashFailurePersistentActor]()
  }
  
  "Stashing(unstashAll called in handler) in a persistent actor" must {
    behave like stash[UserStashWithinHandlerPersistentActor]()
    behave like stashWithSeveralMessages[UserStashWithinHandlerManyPersistentActor]()
    behave like stashUnderFailures[UserStashWithinHandlerFailureCallbackPersistentActor]()
  }
  
}

class SteppingInMemPersistentActorStashingSpec extends PersistenceSpec(
  SteppingInmemJournal.config("persistence-stash").withFallback(PersistenceSpec.config("stepping-inmem", "SteppingInMemPersistentActorStashingSpec")))
  with ImplicitSender {
  import PersistentActorStashingSpec._

  def stash[T <: NamedPersistentActor : ClassTag](): Unit = {
    "handle async callback not happening until next message has been stashed" in {
      val persistentActor = namedPersistentActor[T]
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

  "Stashing in a persistent actor mixed with persistAsync" must {
    behave like stash[AsyncStashingPersistentActor]()
  }
  
  "Stashing(unstashAll called in handler) in a persistent actor mixed with persistAsync" must {
    behave like stash[AsyncStashingWithinHandlerPersistentActor]()
  }

}

class LeveldbPersistentActorStashingSpec extends PersistentActorStashingSpec(PersistenceSpec.config("leveldb", "LeveldbPersistentActorStashingSpec"))
class InmemPersistentActorStashingSpec extends PersistentActorStashingSpec(PersistenceSpec.config("inmem", "InmemPersistentActorStashingSpec"))