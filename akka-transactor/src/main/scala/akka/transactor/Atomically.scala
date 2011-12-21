/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor

import scala.concurrent.stm.InTxn

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
