/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor

import scala.concurrent.stm._

/**
 * Java API.
 *
 * For creating Java-friendly coordinated atomic blocks.
 *
 * @see [[akka.transactor.Coordinated]]
 */
trait Atomically {
  def atomically(txn: InTxn): Unit
}

/**
 * Java API.
 *
 * For creating completion handlers.
 */
trait CompletionHandler {
  def handle(status: Txn.Status): Unit
}

/**
 * Java API.
 *
 * To ease some of the pain of using Scala STM from Java until
 * the proper Java API is created.
 */
object Stm {
  /**
   * Create an STM Ref with an initial value.
   */
  def ref[A](initialValue: A): Ref[A] = Ref(initialValue)

  /**
   * Add a CompletionHandler to run after the current transaction
   * has committed.
   */
  def afterCommit(handler: CompletionHandler): Unit = {
    val txn = Txn.findCurrent
    if (txn.isDefined) Txn.afterCommit(status ⇒ handler.handle(status))(txn.get)
  }

  /**
   * Add a CompletionHandler to run after the current transaction
   * has rolled back.
   */
  def afterRollback(handler: CompletionHandler): Unit = {
    val txn = Txn.findCurrent
    if (txn.isDefined) Txn.afterRollback(status ⇒ handler.handle(status))(txn.get)
  }

  /**
   * Add a CompletionHandler to run after the current transaction
   * has committed or rolled back.
   */
  def afterCompletion(handler: CompletionHandler): Unit = {
    val txn = Txn.findCurrent
    if (txn.isDefined) Txn.afterCompletion(status ⇒ handler.handle(status))(txn.get)
  }
}
