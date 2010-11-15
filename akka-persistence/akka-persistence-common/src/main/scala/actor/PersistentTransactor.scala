package akka.persistence.common.actor

import akka.stm.TransactionalVector
import akka.actor.{ActorRef, Actor, Transactor}

trait PersistentTransactor extends Transactor {
  //somehow wrap message loop and catch a final commit failure, send "begin" snapshot + unapplied portion of the tx log to
  //recoverymanager actor
  ///do FSM to do start become recovering become active
}


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
}