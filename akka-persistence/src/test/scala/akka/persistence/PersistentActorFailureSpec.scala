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

object PersistentActorFailureSpec {
  import PersistentActorSpec.Evt

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
        else
          sender() ! ReplayFailure(new IllegalArgumentException(s"blahonga $fromSnr $toSnr"))
    }

    def isWrong(w: WriteMessages): Boolean =
      w.messages.exists {
        case PersistentRepr(Evt(s: String), _) ⇒ s.contains("wrong")
        case x                                 ⇒ false
      }

    override def receive = failingReceive.orElse(super.receive)
  }

  class Supervisor(testActor: ActorRef) extends Actor {
    override def supervisorStrategy = OneForOneStrategy(loggingEnabled = false) {
      case e ⇒ testActor ! e; SupervisorStrategy.Stop
    }

    def receive = {
      case props: Props ⇒ sender() ! context.actorOf(props)
      case m            ⇒ sender() ! m
    }
  }
}

class PersistentActorFailureSpec extends AkkaSpec(PersistenceSpec.config("inmem", "SnapshotFailureRobustnessSpec", extraConfig = Some(
  """
  akka.persistence.journal.inmem.class = "akka.persistence.PersistentActorFailureSpec$FailingInmemJournal"
  """))) with PersistenceSpec with ImplicitSender {

  import PersistentActorSpec._
  import PersistentActorFailureSpec._

  "A persistent actor" must {
    "throw ActorKilledException if recovery from persisted events fail" in {
      val persistentActor = namedPersistentActor[Behavior1PersistentActor]
      persistentActor ! Cmd("a")
      persistentActor ! GetState
      expectMsg(List("a-1", "a-2"))

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
      expectNoMsg(500.millis)
    }
  }
}
