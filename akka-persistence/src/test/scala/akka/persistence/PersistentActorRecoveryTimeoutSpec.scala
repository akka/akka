package akka.persistence

import akka.actor.Status.Failure
import akka.actor.{ Actor, ActorRef, Props }
import akka.persistence.journal.SteppingInmemJournal
import akka.testkit.{ AkkaSpec, ImplicitSender, TestProbe }
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object PersistentActorRecoveryTimeoutSpec {
  val journalId = "persistent-actor-recovery-timeout-spec"

  def config =
    SteppingInmemJournal.config(PersistentActorRecoveryTimeoutSpec.journalId).withFallback(
      ConfigFactory.parseString(
        """
          |akka.persistence.journal.stepping-inmem.recovery-event-timeout=100ms
        """.stripMargin)).withFallback(PersistenceSpec.config("stepping-inmem", "PersistentActorRecoveryTimeoutSpec"))

  class TestActor(probe: ActorRef) extends NamedPersistentActor("recovery-timeout-actor") {
    override def receiveRecover: Receive = Actor.emptyBehavior

    override def receiveCommand: Receive = {
      case x ⇒ persist(x) { _ ⇒
        sender() ! x
      }
    }

    override protected def onRecoveryFailure(cause: Throwable, event: Option[Any]): Unit = {
      probe ! Failure(cause)
    }
  }

}

class PersistentActorRecoveryTimeoutSpec extends AkkaSpec(PersistentActorRecoveryTimeoutSpec.config) with ImplicitSender {

  import PersistentActorRecoveryTimeoutSpec.journalId

  "The recovery timeout" should {

    "fail recovery if timeout is not met when recovering" in {
      val probe = TestProbe()
      val persisting = system.actorOf(Props(classOf[PersistentActorRecoveryTimeoutSpec.TestActor], probe.ref))

      awaitAssert(SteppingInmemJournal.getRef(journalId), 3.seconds)
      val journal = SteppingInmemJournal.getRef(journalId)

      // initial read highest
      SteppingInmemJournal.step(journal)

      persisting ! "A"
      SteppingInmemJournal.step(journal)
      expectMsg("A")

      watch(persisting)
      system.stop(persisting)
      expectTerminated(persisting)

      // now replay, but don't give the journal any tokens to replay events
      // so that we cause the timeout to trigger
      val replaying = system.actorOf(Props(classOf[PersistentActorRecoveryTimeoutSpec.TestActor], probe.ref))
      watch(replaying)

      // initial read highest
      SteppingInmemJournal.step(journal)

      probe.expectMsgType[Failure].cause shouldBe a[RecoveryTimedOut]
      expectTerminated(replaying)

    }

  }

}
