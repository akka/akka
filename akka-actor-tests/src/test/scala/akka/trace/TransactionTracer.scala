/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.trace

import akka.actor.{ ActorRef, ExtendedActorSystem }
import akka.util.ByteString
import com.typesafe.config.Config
import scala.util.DynamicVariable

/**
 * A global thread-local transaction for transaction tracing.
 */
object Transaction {
  val empty = ""

  val transaction = new DynamicVariable[String](empty)

  def value: String = transaction.value
  def value_=(newValue: String) = transaction.value = newValue

  def withValue[T](newValue: String)(thunk: ⇒ T) = transaction.withValue(newValue)(thunk)

  def clear(): Unit = transaction.value = empty
}

/**
 * Example tracer implementation that threads a transaction identifier
 * through message flows using the trace context and a thread-local.
 */
class TransactionTracer(config: Config) extends ContextOnlyTracer {
  def getContext(): Any = Transaction.value

  def setContext(context: Any): Unit =
    context match {
      case transaction: String ⇒ Transaction.value = transaction
      case _                   ⇒
    }

  def clearContext(): Unit = Transaction.clear()

  def identifier: Int = 42

  def serializeContext(system: ExtendedActorSystem, context: Any): Array[Byte] = {
    context match {
      case transaction: String ⇒ ByteString(transaction, "UTF-8").toArray
      case _                   ⇒ Array.empty[Byte]
    }
  }

  def deserializeContext(system: ExtendedActorSystem, context: Array[Byte]): Any = ByteString(context).utf8String
}
