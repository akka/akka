/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.stm

import se.scalablesolutions.akka.util.Logging

import org.multiverse.api.{Transaction => MultiverseTransaction}
import org.multiverse.templates.TransactionalCallable

/**
 * Global transaction management, global in the context of multiple threads.
 * Use this if you need to have one transaction span multiple threads (or Actors).
 * <p/>
 * Example of atomic transaction management using the atomic block:
 * <p/>
 * <pre>
 * import se.scalablesolutions.akka.stm.global._
 *
 * atomic  {
 *   // do something within a transaction
 * }
 * </pre>
 */
class GlobalStm extends TransactionManagement with Logging {

  val DefaultGlobalTransactionConfig = TransactionConfig()
  val DefaultGlobalTransactionFactory = TransactionFactory(DefaultGlobalTransactionConfig, "DefaultGlobalTransaction")

  def atomic[T](body: => T)(implicit factory: TransactionFactory = DefaultGlobalTransactionFactory): T = atomic(factory)(body)

  def atomic[T](factory: TransactionFactory)(body: => T): T = {
    factory.boilerplate.execute(new TransactionalCallable[T]() {
      def call(mtx: MultiverseTransaction): T = {
        if (!isTransactionSetInScope) createNewTransactionSet
        factory.addHooks
        val result = body
        val txSet = getTransactionSetInScope
        log.trace("Committing global transaction [" + mtx + "]\n\tand joining transaction set [" + txSet + "]")
        try {
          txSet.tryJoinCommit(
            mtx,
            TransactionConfig.DefaultTimeout.length,
            TransactionConfig.DefaultTimeout.unit)
        // Need to catch IllegalStateException until we have fix in Multiverse, since it throws it by mistake
        } catch { case e: IllegalStateException => {} }
        result
      }
    })
  }
}

