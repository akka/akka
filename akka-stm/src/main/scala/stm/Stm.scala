/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.stm

import org.multiverse.api.{StmUtils => MultiverseStmUtils}
import org.multiverse.api.{Transaction => MultiverseTransaction}
import org.multiverse.templates.{TransactionalCallable, OrElseTemplate}

/**
 * Stm trait that defines the atomic block for local transactions.
 * <p/>
 * If you need to coordinate transactions across actors @see Coordinated.
 * <p/>
 * Example of atomic transaction management using the atomic block (in Scala).
 * <p/>
 * <pre>
 * import akka.stm._
 *
 * atomic  {
 *   // do something within a transaction
 * }
 * </pre>
 * <p/>
 * @see Atomic for creating atomic blocks in Java.
 */
trait Stm {
  val DefaultTransactionFactory = TransactionFactory(DefaultTransactionConfig, "DefaultTransaction")

  def atomic[T](body: => T)(implicit factory: TransactionFactory = DefaultTransactionFactory): T =
    atomic(factory)(body)

  def atomic[T](factory: TransactionFactory)(body: => T): T = {
    factory.boilerplate.execute(new TransactionalCallable[T]() {
      def call(mtx: MultiverseTransaction): T = {
        factory.addHooks
        body
      }
    })
  }
}

/**
 * Stm utils for scheduling transaction lifecycle tasks and for blocking transactions.
 */
trait StmUtil {
  /**
   * Schedule a deferred task on the thread local transaction (use within an atomic).
   * This is executed when the transaction commits.
   */
  def deferred[T](body: => T): Unit =
    MultiverseStmUtils.scheduleDeferredTask(new Runnable { def run = body })

  /**
   * Schedule a compensating task on the thread local transaction (use within an atomic).
   * This is executed when the transaction aborts.
   */
  def compensating[T](body: => T): Unit =
    MultiverseStmUtils.scheduleCompensatingTask(new Runnable { def run = body })

  /**
   * STM retry for blocking transactions (use within an atomic).
   * Can be used to wait for a condition.
   */
  def retry = MultiverseStmUtils.retry

  /**
   * Use either-orElse to combine two blocking transactions.
   * Usage:
   * <pre>
   * either {
   *   ...
   * } orElse {
   *   ...
   * }
   * </pre>
   */
  def either[T](firstBody: => T) = new {
    def orElse(secondBody: => T) = new OrElseTemplate[T] {
      def either(mtx: MultiverseTransaction) = firstBody
      def orelse(mtx: MultiverseTransaction) = secondBody
    }.execute()
  }
}



