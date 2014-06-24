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
import akka.persistence.journal.AsyncWriteTarget.{ ReplayFailure, ReplaySuccess, ReplayMessages }

import scala.language.postfixOps
import akka.persistence.journal.AsyncWriteTarget.ReplayFailure
import scala.Some
import akka.actor.OneForOneStrategy
import akka.persistence.journal.AsyncWriteTarget.ReplayMessages

object PersistentActorFailureSpec {
  class FailingInmemJournal extends AsyncWriteProxy {
    import AsyncWriteProxy.SetStore

    val timeout = Timeout(5 seconds)

    override def preStart(): Unit = {
      super.preStart()
      self ! SetStore(context.actorOf(Props[FailingInmemStore]))
    }
  }

  class FailingInmemStore extends InmemStore {
    def failingReceive: Receive = {
      case ReplayMessages(pid, fromSnr, toSnr, max) ⇒
        val readFromStore = read(pid, fromSnr, toSnr, max)
        if (readFromStore.length == 0)
          sender() ! ReplaySuccess
        else
          sender() ! ReplayFailure(new IllegalArgumentException(s"blahonga $fromSnr $toSnr"))
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
    |akka.persistence.journal.inmem.class = "akka.persistence.PersistentActorFailureSpec$FailingInmemJournal"
  """.stripMargin))) with PersistenceSpec with ImplicitSender {

  import PersistentActorSpec._
  import PersistentActorFailureSpec._

  override protected def beforeEach() {
    super.beforeEach()

    val processor = namedProcessor[Behavior1Processor]
    processor ! Cmd("a")
    processor ! GetState
    expectMsg(List("a-1", "a-2"))
  }

  "A persistent actor" must {
    "throw ActorKilledException if recovery from persisted events fail" in {
      system.actorOf(Props(classOf[Supervisor], testActor)) ! Props(classOf[Behavior1Processor], name)
      expectMsgType[ActorRef]
      expectMsgType[ActorKilledException]
    }
  }
}
