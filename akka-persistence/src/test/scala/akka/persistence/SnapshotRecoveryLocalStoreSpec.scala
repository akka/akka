package akka.persistence

import akka.actor.{ Props, Actor, ActorRef }
import akka.testkit.{ TestProbe, ImplicitSender, AkkaSpec }

object SnapshotRecoveryLocalStoreSpec {
  val persistenceId = "europe"
  val extendedName = persistenceId + "italy"

  case object TakeSnapshot

  class SaveSnapshotTestPersistentActor(name: String, probe: ActorRef) extends NamedPersistentActor(name) {
    var state = s"State for actor ${name}"
    def receiveCommand = {
      case TakeSnapshot            ⇒ saveSnapshot(state)
      case SaveSnapshotSuccess(md) ⇒ probe ! md.sequenceNr
      case GetState                ⇒ probe ! state
    }
    def receiveRecover = {
      case _ ⇒
    }
  }

  class LoadSnapshotTestPersistentActor(name: String, probe: ActorRef) extends NamedPersistentActor(name) {
    def receiveCommand = {
      case _ ⇒
    }
    def receiveRecover = {
      case SnapshotOffer(md, s) ⇒ probe ! ((md, s))
      case other                ⇒ probe ! other
    }
    override def preStart() = ()
  }
}

class SnapshotRecoveryLocalStoreSpec extends AkkaSpec(PersistenceSpec.config("inmem", "SnapshotRecoveryLocalStoreSpec")) with PersistenceSpec with ImplicitSender {

  import SnapshotRecoveryLocalStoreSpec._

  override protected def beforeEach() {
    super.beforeEach()

    val persistentActor1 = system.actorOf(Props(classOf[SaveSnapshotTestPersistentActor], persistenceId, testActor))
    val persistentActor2 = system.actorOf(Props(classOf[SaveSnapshotTestPersistentActor], extendedName, testActor))
    persistentActor1 ! TakeSnapshot
    persistentActor2 ! TakeSnapshot
    expectMsgAllOf(0L, 0L)

  }

  "A persistent actor which is persisted at the same time as another actor whose persistenceId is an extension of the first " must {
    "recover state only from its own correct snapshot file" in {

      val recoveringActor = system.actorOf(Props(classOf[LoadSnapshotTestPersistentActor], persistenceId, testActor))

      recoveringActor ! Recover()

      expectMsgPF() {
        case (SnapshotMetadata(pid, seqNo, timestamp), state) ⇒
          pid should be(persistenceId)
      }
      expectMsg(RecoveryCompleted)
    }

  }
}