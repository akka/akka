/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence

import akka.actor._
import akka.event.Logging
import akka.event.Logging.Warning
import akka.persistence.journal.inmem.InmemJournal
import akka.testkit.{ EventFilter, ImplicitSender, TestEvent }

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object PersistentActorDeleteFailureSpec {

  case class DeleteTo(n: Long)
  class SimulatedException(msg: String) extends RuntimeException(msg) with NoStackTrace
  class SimulatedSerializationException(msg: String) extends RuntimeException(msg) with NoStackTrace

  class DeleteFailingInmemJournal extends InmemJournal {
    override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] =
      Future.failed(new SimulatedException("Boom! Unable to delete events!"))
  }

  class DoesNotHandleDeleteFailureActor(name: String, probe: ActorRef) extends PersistentActor {
    override def persistenceId = name
    override def receiveCommand: Receive = {
      case DeleteTo(n) ⇒ deleteMessages(n)
    }
    override def receiveRecover: Receive = Actor.emptyBehavior
  }

  class HandlesDeleteFailureActor(name: String, probe: ActorRef) extends PersistentActor {
    override def persistenceId = name
    override def receiveCommand: Receive = {
      case DeleteTo(n)              ⇒ deleteMessages(n)
      case f: DeleteMessagesFailure ⇒ probe ! f
    }
    override def receiveRecover: Receive = Actor.emptyBehavior
  }

}

class PersistentActorDeleteFailureSpec extends PersistenceSpec(PersistenceSpec.config("inmem", "SnapshotFailureRobustnessSpec", extraConfig = Some(
  """
  akka.persistence.journal.inmem.class = "akka.persistence.PersistentActorDeleteFailureSpec$DeleteFailingInmemJournal"
  """))) with ImplicitSender {
  import PersistentActorDeleteFailureSpec._

  system.eventStream.publish(TestEvent.Mute(EventFilter[akka.pattern.AskTimeoutException]()))

  "A persistent actor" must {
    "have default warn logging be triggered, when deletion failed" in {
      val persistentActor = system.actorOf(Props(classOf[DoesNotHandleDeleteFailureActor], name, testActor))
      system.eventStream.subscribe(testActor, classOf[Logging.Warning])
      persistentActor ! DeleteTo(100)
      val message = expectMsgType[Warning].message.toString
      message should include("Failed to deleteMessages")
      message should include("Boom! Unable to delete events!") // the `cause` message
    }

    "be receive an DeleteMessagesFailure when deletion failed, and the default logging should not be triggered" in {
      val persistentActor = system.actorOf(Props(classOf[HandlesDeleteFailureActor], name, testActor))
      system.eventStream.subscribe(testActor, classOf[Logging.Warning])
      persistentActor ! DeleteTo(100)
      expectMsgType[DeleteMessagesFailure]
      expectNoMsg(100.millis) // since the actor handled the message, we do not issue warn logging automatically
    }

  }
}

