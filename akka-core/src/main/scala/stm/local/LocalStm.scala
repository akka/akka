/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.stm

import se.scalablesolutions.akka.util.Logging

import org.multiverse.api.{Transaction => MultiverseTransaction}
import org.multiverse.templates.TransactionalCallable

/**
 * Local transaction management, local in the context of threads.
 * Use this if you do <b>not</b> need to have one transaction span
 * multiple threads (or Actors).
 * <p/>
 * Example of atomic transaction management using the atomic block.
 * <p/>
 * <pre>
 * import se.scalablesolutions.akka.stm.local._
 *
 * atomic  {
 *   // do something within a transaction
 * }
 * </pre>
 */
class LocalStm extends TransactionManagement with Logging {

  val DefaultLocalTransactionConfig = TransactionConfig()
  val DefaultLocalTransactionFactory = TransactionFactory(DefaultLocalTransactionConfig, "DefaultLocalTransaction")

  def atomic[T](body: => T)(implicit factory: TransactionFactory = DefaultLocalTransactionFactory): T = atomic(factory)(body)

  def atomic[T](factory: TransactionFactory)(body: => T): T = {
    factory.boilerplate.execute(new TransactionalCallable[T]() {
      def call(mtx: MultiverseTransaction): T = {
        factory.addHooks
        val result = body
        log.trace("Committing local transaction [" + mtx + "]")
        result
      }
    })
  }
}
