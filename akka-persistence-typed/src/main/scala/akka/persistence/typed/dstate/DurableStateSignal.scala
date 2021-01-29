/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.dstate

import akka.actor.typed.Signal
import akka.annotation.DoNotInherit

/**
 * Supertype for all Akka Persistence Typed - Durable State specific signals
 *
 * Not for user extension
 */
@DoNotInherit
sealed trait DurableStateSignal  extends Signal

@DoNotInherit sealed abstract class RecoveryCompleted extends DurableStateSignal
case object RecoveryCompleted extends RecoveryCompleted {
  def instance: RecoveryCompleted = this
}

final case class RecoveryFailed(failure: Throwable) extends DurableStateSignal {

  /**
   * Java API
   */
  def getFailure(): Throwable = failure
}


case object PersistenceCompleted extends DurableStateSignal {

  /**
   * Java API
   */
  def instance: PersistenceCompleted.type = this
}

final case class PersistenceFailed(failure: Throwable) extends DurableStateSignal {

  /**
   * Java API
   */
  def getFailure(): Throwable = failure

}


case object DeletionCompleted extends DurableStateSignal {

  /**
   * Java API
   */
  def instance: DeletionCompleted.type = this
}


final case class DeletionFailed(failure: Throwable) extends DurableStateSignal {

  /**
   * Java API
   */
  def getFailure(): Throwable = failure

}