/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.stm

import org.multiverse.api.{ StmUtils ⇒ MultiverseStmUtils }
import org.multiverse.api.{ Transaction ⇒ MultiverseTransaction }
import org.multiverse.templates.{ TransactionalCallable, OrElseTemplate }

object Stm {
  /**
   * Check whether there is an active Multiverse transaction.
   */
  def activeTransaction() = {
    val tx = org.multiverse.api.ThreadLocalTransaction.getThreadLocalTransaction
    (tx ne null) && !tx.getStatus.isDead
  }
}

/**
 * Defines the atomic block for local transactions. Automatically imported with:
 *
 * {{{
 * import akka.stm._
 * }}}
 * <br/>
 *
 * If you need to coordinate transactions across actors see [[akka.stm.Coordinated]].
 * <br/><br/>
 *
 * Example of using the atomic block ''(Scala)''
 *
 * {{{
 * atomic  {
 *   // do something within a transaction
 * }
 * }}}
 *
 * @see [[akka.stm.Atomic]] for creating atomic blocks in Java.
 * @see [[akka.stm.StmUtil]] for useful methods to combine with `atomic`
 */
trait Stm {
  val DefaultTransactionFactory = TransactionFactory(DefaultTransactionConfig, "DefaultTransaction")

  def atomic[T](body: ⇒ T)(implicit factory: TransactionFactory = DefaultTransactionFactory): T =
    atomic(factory)(body)

  def atomic[T](factory: TransactionFactory)(body: ⇒ T): T = {
    factory.boilerplate.execute(new TransactionalCallable[T]() {
      def call(mtx: MultiverseTransaction): T = body
    })
  }
}

/**
 * Stm utility methods for scheduling transaction lifecycle tasks and for blocking transactions.
 * Automatically imported with:
 *
 * {{{
 * import akka.stm._
 * }}}
 * <br/>
 *
 * Schedule a deferred task on the thread local transaction (use within an atomic).
 * This is executed when the transaction commits.
 *
 * {{{
 * atomic {
 *   deferred {
 *     // executes when transaction successfully commits
 *   }
 * }
 * }}}
 * <br/>
 *
 * Schedule a compensating task on the thread local transaction (use within an atomic).
 * This is executed when the transaction aborts.
 *
 * {{{
 * atomic {
 *   compensating {
 *     // executes when transaction aborts
 *   }
 * }
 * }}}
 * <br/>
 *
 * STM retry for blocking transactions (use within an atomic).
 * Can be used to wait for a condition.
 *
 * {{{
 * atomic {
 *   if (!someCondition) retry
 *   // ...
 * }
 * }}}
 * <br/>
 *
 * Use either-orElse to combine two blocking transactions.
 *
 * {{{
 * atomic {
 *   either {
 *     // ...
 *   } orElse {
 *     // ...
 *   }
 * }
 * }}}
 * <br/>
 */
trait StmUtil {
  /**
   * Schedule a deferred task on the thread local transaction (use within an atomic).
   * This is executed when the transaction commits.
   */
  def deferred[T](body: ⇒ T): Unit =
    MultiverseStmUtils.scheduleDeferredTask(new Runnable { def run = body })

  /**
   * Schedule a compensating task on the thread local transaction (use within an atomic).
   * This is executed when the transaction aborts.
   */
  def compensating[T](body: ⇒ T): Unit =
    MultiverseStmUtils.scheduleCompensatingTask(new Runnable { def run = body })

  /**
   * STM retry for blocking transactions (use within an atomic).
   * Can be used to wait for a condition.
   */
  def retry() = MultiverseStmUtils.retry

  /**
   * Use either-orElse to combine two blocking transactions.
   */
  def either[T](firstBody: ⇒ T) = new {
    def orElse(secondBody: ⇒ T) = new OrElseTemplate[T] {
      def either(mtx: MultiverseTransaction) = firstBody
      def orelse(mtx: MultiverseTransaction) = secondBody
    }.execute()
  }
}

/**
 * Stm utility methods for using from Java.
 */
object StmUtils {
  /**
   * Schedule a deferred task on the thread local transaction (use within an atomic).
   * This is executed when the transaction commits.
   */
  def deferred(runnable: Runnable): Unit = MultiverseStmUtils.scheduleDeferredTask(runnable)

  /**
   * Schedule a compensating task on the thread local transaction (use within an atomic).
   * This is executed when the transaction aborts.
   */
  def compensating(runnable: Runnable): Unit = MultiverseStmUtils.scheduleCompensatingTask(runnable)

  /**
   * STM retry for blocking transactions (use within an atomic).
   * Can be used to wait for a condition.
   */
  def retry = MultiverseStmUtils.retry
}

/**
 * Use EitherOrElse to combine two blocking transactions (from Java).
 */
abstract class EitherOrElse[T] extends OrElseTemplate[T] {
  def either(mtx: MultiverseTransaction) = either
  def orelse(mtx: MultiverseTransaction) = orElse

  def either: T
  def orElse: T
}
