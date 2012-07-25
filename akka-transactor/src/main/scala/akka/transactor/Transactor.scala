/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor

import language.postfixOps

import akka.actor.{ Actor, ActorRef }
import scala.concurrent.stm.InTxn

/**
 * Used for specifying actor refs and messages to send to during coordination.
 */
case class SendTo(actor: ActorRef, message: Option[Any] = None)

/**
 * An actor with built-in support for coordinated transactions.
 *
 * Transactors implement the general pattern for using [[akka.transactor.Coordinated]] where
 * coordination messages are sent to other transactors then the coordinated transaction is
 * entered. Transactors can also accept explicitly sent `Coordinated` messages.
 * <br/><br/>
 *
 * Simple transactors will just implement the `atomically` method which is similar to
 * the actor `receive` method but runs within a coordinated transaction.
 *
 * Example of a simple transactor that will join a coordinated transaction:
 *
 * {{{
 * class Counter extends Transactor {
 *   val count = Ref(0)
 *
 *   def atomically = implicit txn => {
 *     case Increment => count transform (_ + 1)
 *   }
 * }
 * }}}
 * <br/>
 *
 * To coordinate with other transactors override the `coordinate` method.
 * The `coordinate` method maps a message to a set of [[akka.transactor.SendTo]]
 * objects, pairs of `ActorRef` and a message.
 * You can use the `include` and `sendTo` methods to easily coordinate with other transactors.
 * The `include` method will send on the same message that was received to other transactors.
 * The `sendTo` method allows you to specify both the actor to send to, and message to send.
 *
 * Example of coordinating an increment:
 *
 * {{{
 * class FriendlyCounter(friend: ActorRef) extends Transactor {
 *   val count = Ref(0)
 *
 *   override def coordinate = {
 *     case Increment => include(friend)
 *   }
 *
 *   def atomically = implicit txn => {
 *     case Increment => count transform (_ + 1)
 *   }
 * }
 * }}}
 * <br/>
 *
 * Using `include` to include more than one transactor:
 *
 * {{{
 * override def coordinate = {
 *   case Message => include(actor1, actor2, actor3)
 * }
 * }}}
 * <br/>
 *
 * Using `sendTo` to coordinate transactions but send on a different message
 * than the one that was received:
 *
 * {{{
 * override def coordinate = {
 *   case Message => sendTo(someActor -> SomeOtherMessage)
 *   case SomeMessage => sendTo(actor1 -> Message1, actor2 -> Message2)
 * }
 * }}}
 * <br/>
 *
 * To execute directly before or after the coordinated transaction, override
 * the `before` and `after` methods. These methods also expect partial functions
 * like the receive method. They do not execute within the transaction.
 *
 * To completely bypass coordinated transactions override the `normally` method.
 * Any message matched by `normally` will not be matched by the other methods,
 * and will not be involved in coordinated transactions. In this method you
 * can implement normal actor behavior, or use the normal STM atomic for
 * local transactions.
 *
 * @see [[akka.transactor.Coordinated]] for more information about the underlying mechanism
 */
trait Transactor extends Actor {
  private val settings = TransactorExtension(context.system)

  /**
   * Implement a general pattern for using coordinated transactions.
   */
  final def receive = {
    case coordinated @ Coordinated(message) ⇒ {
      val others = (coordinate orElse alone)(message)
      for (sendTo ← others) {
        sendTo.actor ! coordinated(sendTo.message.getOrElse(message))
      }
      (before orElse doNothing)(message)
      coordinated.atomic { txn ⇒ (atomically(txn) orElse doNothing)(message) }
      (after orElse doNothing)(message)
    }
    case message ⇒ {
      if (normally.isDefinedAt(message)) normally(message)
      else receive(Coordinated(message)(settings.CoordinatedTimeout))
    }
  }

  /**
   * Override this method to coordinate with other transactors.
   * The other transactors are added to the coordinated transaction barrier
   * and sent a Coordinated message. The message to send can be specified
   * or otherwise the same message as received is sent. Use the 'include' and
   * 'sendTo' methods to easily create the set of transactors to be involved.
   */
  def coordinate: PartialFunction[Any, Set[SendTo]] = alone

  /**
   * Default coordination - no other transactors.
   */
  def alone: PartialFunction[Any, Set[SendTo]] = { case _ ⇒ nobody }

  /**
   * Empty set of transactors to send to.
   */
  def nobody: Set[SendTo] = Set.empty

  /**
   * Include other actors in this coordinated transaction and send
   * them the same message as received. Use as the result in 'coordinated'.
   */
  def include(actors: ActorRef*): Set[SendTo] = actors map (SendTo(_)) toSet

  /**
   * Include other actors in this coordinated transaction and specify the message
   * to send by providing ActorRef -> Message pairs. Use as the result in 'coordinated'.
   */
  def sendTo(pairs: (ActorRef, Any)*): Set[SendTo] = pairs map (p ⇒ SendTo(p._1, Some(p._2))) toSet

  /**
   * A Receive block that runs before the coordinated transaction is entered.
   */
  def before: Receive = doNothing

  /**
   * The Receive block to run inside the coordinated transaction.
   * This is a function from InTxn to Receive block.
   *
   * For example:
   * {{{
   * def atomically = implicit txn => {
   *   case Increment => count transform (_ + 1)
   * }
   * }}}
   */
  def atomically: InTxn ⇒ Receive

  /**
   * A Receive block that runs after the coordinated transaction.
   */
  def after: Receive = doNothing

  /**
   * Bypass transactionality and behave like a normal actor.
   */
  def normally: Receive = doNothing

  /**
   * Default catch-all for the different Receive methods.
   */
  def doNothing: Receive = EmptyReceive
}

private[akka] object EmptyReceive extends PartialFunction[Any, Unit] {
  def apply(any: Any): Unit = ()
  def isDefinedAt(any: Any): Boolean = false
}
