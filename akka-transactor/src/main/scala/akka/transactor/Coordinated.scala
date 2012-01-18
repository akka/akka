/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor

import akka.AkkaException
import akka.util.Timeout
import scala.concurrent.stm.{ CommitBarrier, InTxn }
import java.util.concurrent.Callable

/**
 * Akka-specific exception for coordinated transactions.
 */
class CoordinatedTransactionException(message: String, cause: Throwable = null) extends AkkaException(message, cause) {
  def this(msg: String) = this(msg, null);
}

/**
 * Coordinated transactions across actors.
 */
object Coordinated {
  def apply(message: Any = null)(implicit timeout: Timeout) = new Coordinated(message, createInitialMember(timeout))

  def unapply(c: Coordinated): Option[Any] = Some(c.message)

  def createInitialMember(timeout: Timeout) = CommitBarrier(timeout.duration.toMillis).addMember()
}

/**
 * `Coordinated` is a message wrapper that adds a `CommitBarrier` for explicitly
 * coordinating transactions across actors or threads.
 *
 * Creating a `Coordinated` will create a commit barrier with initially one member.
 * For each member in the coordination set a transaction is expected to be created using
 * the coordinated atomic method, or the coordination cancelled using the cancel method.
 *
 * The number of included members must match the number of transactions, otherwise a
 * successful transaction cannot be coordinated.
 * <br/><br/>
 *
 * To start a new coordinated transaction set that you will also participate in just create
 * a `Coordinated` object:
 *
 * {{{
 * val coordinated = Coordinated()
 * }}}
 * <br/>
 *
 * To start a coordinated transaction that you won't participate in yourself you can create a
 * `Coordinated` object with a message and send it directly to an actor. The recipient of the message
 * will be the first member of the coordination set:
 *
 * {{{
 * actor ! Coordinated(Message)
 * }}}
 * <br/>
 *
 * To receive a coordinated message in an actor simply match it in a case statement:
 *
 * {{{
 * def receive = {
 *   case coordinated @ Coordinated(Message) => ...
 * }
 * }}}
 * <br/>
 *
 * To include another actor in the same coordinated transaction set that you've created or
 * received, use the apply method on that object. This will increment the number of parties
 * involved by one and create a new `Coordinated` object to be sent.
 *
 * {{{
 * actor ! coordinated(Message)
 * }}}
 * <br/>
 *
 * To enter the coordinated transaction use the atomic method of the coordinated object:
 *
 * {{{
 * coordinated.atomic { implicit txn =>
 *   // do something in transaction ...
 * }
 * }}}
 *
 * The coordinated transaction will wait for the other transactions before committing.
 * If any of the coordinated transactions fail then they all fail.
 *
 * @see [[akka.actor.Transactor]] for an actor that implements coordinated transactions
 */
class Coordinated(val message: Any, member: CommitBarrier.Member) {

  // Java API constructors

  def this(message: Any, timeout: Timeout) = this(message, Coordinated.createInitialMember(timeout))

  def this(timeout: Timeout) = this(null, Coordinated.createInitialMember(timeout))

  /**
   * Create a new Coordinated object and increment the number of members by one.
   * Use this method to ''pass on'' the coordination.
   */
  def apply(msg: Any) = {
    new Coordinated(msg, member.commitBarrier.addMember())
  }

  /**
   * Create a new Coordinated object but *do not* increment the number of members by one.
   * Only use this method if you know this is what you need.
   */
  def noIncrement(msg: Any) = new Coordinated(msg, member)

  /**
   * Java API: get the message for this Coordinated.
   */
  def getMessage() = message

  /**
   * Java API: create a new Coordinated object and increment the number of members by one.
   * Use this method to ''pass on'' the coordination.
   */
  def coordinate(msg: Any) = apply(msg)

  /**
   * Delimits the coordinated transaction. The transaction will wait for all other transactions
   * in this coordination before committing. The timeout is specified when creating the Coordinated.
   *
   * @throws CoordinatedTransactionException if the coordinated transaction fails.
   */
  def atomic[A](body: InTxn ⇒ A): A = {
    member.atomic(body) match {
      case Right(result) ⇒ result
      case Left(CommitBarrier.MemberUncaughtExceptionCause(x)) ⇒
        throw new CoordinatedTransactionException("Exception in coordinated atomic", x)
      case Left(cause) ⇒
        throw new CoordinatedTransactionException("Failed due to " + cause)
    }
  }

  /**
   * Java API: coordinated atomic method that accepts a `java.lang.Runnable`.
   * Delimits the coordinated transaction. The transaction will wait for all other transactions
   * in this coordination before committing. The timeout is specified when creating the Coordinated.
   *
   * @throws CoordinatedTransactionException if the coordinated transaction fails.
   */
  def atomic(runnable: Runnable): Unit = atomic { _ ⇒ runnable.run }

  /**
   * Java API: coordinated atomic method that accepts a `java.util.concurrent.Callable`.
   * Delimits the coordinated transaction. The transaction will wait for all other transactions
   * in this coordination before committing. The timeout is specified when creating the Coordinated.
   *
   * @throws CoordinatedTransactionException if the coordinated transaction fails.
   */
  def atomic[A](callable: Callable[A]): A = atomic { _ ⇒ callable.call }

  /**
   * An empty coordinated atomic block. Can be used to complete the number of members involved
   * and wait for all transactions to complete.
   */
  def await() = atomic(txn ⇒ ())

  /**
   * Cancel this Coordinated transaction.
   */
  def cancel(info: Any) = member.cancel(CommitBarrier.UserCancel(info))
}
