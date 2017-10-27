/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit

import java.util.UUID

import akka.actor.ActorRef
import akka.persistence._

trait CommonUtils {

  protected def randomPid() = UUID.randomUUID().toString

}

case class NewSnapshot(state: Any)
case object DeleteAllMessages
case class DeleteSomeSnapshot(seqNum: Long)
case class DeleteSomeSnapshotByCriteria(crit: SnapshotSelectionCriteria)
case object AskMessageSeqNum
case object AskSnapshotSeqNum
case class DeleteSomeMessages(upToSeqNum: Long)

class C

case class B(i: Int)

class A(pid: String, notifyOnStateChange: Option[ActorRef]) extends PersistentActor {

  import scala.collection.immutable

  var recovered = immutable.List.empty[Any]
  var snapshotState = 0

  override def receiveRecover = {
    case SnapshotOffer(_, snapshot: Int) ⇒
      snapshotState = snapshot
    case RecoveryCompleted ⇒
      notifyOnStateChange.foreach(_ ! Tuple2(recovered, snapshotState))
    case s ⇒ recovered :+= s
  }

  override def receiveCommand = {
    case AskMessageSeqNum ⇒
      notifyOnStateChange.foreach(_ ! lastSequenceNr)
    case AskSnapshotSeqNum ⇒
      notifyOnStateChange.foreach(_ ! snapshotSequenceNr)
    case d @ DeleteMessagesFailure(_, _) ⇒
      notifyOnStateChange.foreach(_ ! d)
    case d @ DeleteMessagesSuccess(_) ⇒
      notifyOnStateChange.foreach(_ ! d)
    case s: SnapshotProtocol.Response ⇒
      notifyOnStateChange.foreach(_ ! s)
    case DeleteAllMessages ⇒
      deleteMessages(lastSequenceNr)
    case DeleteSomeSnapshot(sn) ⇒
      deleteSnapshot(sn)
    case DeleteSomeSnapshotByCriteria(crit) ⇒
      deleteSnapshots(crit)
    case DeleteSomeMessages(sn) ⇒
      deleteMessages(sn)
    case NewSnapshot(state: Int) ⇒
      snapshotState = state: Int
      saveSnapshot(state)
    case NewSnapshot(other) ⇒
      saveSnapshot(other)
    case s ⇒
      persist(s) { _ ⇒
        sender() ! s
      }
  }

  override def persistenceId = pid
}

