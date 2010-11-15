package akka.persistence.common.actor

import akka.stm.TransactionalVector
import akka.transactor.{Transactor}
import akka.stm.TransactionManagement.transaction
import akka.actor.{FSM, ActorRef, Actor}
import org.multiverse.api.exceptions.TooManyRetriesException
import akka.persistence.common._



trait PersistentTransactor extends Transactor{
  //somehow wrap message loop and catch a final commit failure, send "begin" snapshot + unapplied portion of the tx log to
  //recoverymanager actor
  ///do FSM to do start become recovering become active
  type ElementType

  def recoveryManager: ActorRef


  def atomically = {
    try
    {
      atomicallyWithStorage
    } catch {
      case e: TooManyRetriesException => {

        transaction.get.get.persistentStateMap foreach{

        }
      }
    }
  }


  def atomicallyWithStorage:Unit


}

@serializable sealed trait CommitFailure
case class RefFailure(uuid:String, ref: Array[Byte], error:String) extends CommitFailure
case class VectorFailure(uuid:String, log:Array[Byte],error:String) extends CommitFailure
case class MapFailure(uuid:String, log:Array[Byte], error:String) extends CommitFailure
case class QueueFailure(uuid:String, log:Array[Byte],error:String) extends CommitFailure
case class SortedSetFailure(uuid:String, log:Array[Byte],error:String) extends CommitFailure



trait PersistentTransactorSupervisor extends Actor {
  //send messages to this to start PersistentTransactors,
  // this will start up a recovery manager and wire up the recoverManager ref into PersistentTransactors
}

sealed trait RecoveryMesage

case class CommitFailed(actorRef: ActorRef, snapshot: Any, unappliedLog: TransactionalVector) extends RecoveryMesage

case class RecoverFailedTransaction extends RecoveryMesage

trait PersistentTransactorRecoveryManager extends Actor {
  //log the failed transactions in hawtdb
  //send the failed transactions back to the restarted actor

  protected def receive = {

  }



}