/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.transactor

import akka.config.Config
import akka.stm.{DefaultTransactionConfig, TransactionFactory}

import org.multiverse.api.{Transaction => MultiverseTransaction}
import org.multiverse.commitbarriers.CountDownCommitBarrier
import org.multiverse.templates.TransactionalCallable

/**
 * Coordinated transactions across actors.
 */
object Coordinated {
  val DefaultFactory = TransactionFactory(DefaultTransactionConfig, "DefaultCoordinatedTransaction")
  val Fair = Config.config.getBool("akka.stm.fair", true)

  def apply(message: Any = null) = new Coordinated(message, createBarrier)

  def unapply(c: Coordinated): Option[Any] = Some(c.message)

  def createBarrier = new CountDownCommitBarrier(1, Fair)
}

/**
 * `Coordinated` is a message wrapper that adds a `CountDownCommitBarrier` for explicitly
 * coordinating transactions across actors or threads.
 *
 * Creating a `Coordinated` will create a count down barrier with initially one member.
 * For each member in the coordination set a transaction is expected to be created using
 * the coordinated atomic method. The number of included parties must match the number of
 * transactions, otherwise a successful transaction cannot be coordinated.
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
 * coordinated atomic {
 *   // do something in transaction ...
 * }
 * }}}
 *
 * The coordinated transaction will wait for the other transactions before committing.
 * If any of the coordinated transactions fail then they all fail.
 *
 * @see [[akka.actor.Transactor]] for an actor that implements coordinated transactions
 */
class Coordinated(val message: Any, barrier: CountDownCommitBarrier) {
  def apply(msg: Any) = {
    barrier.incParties(1)
    new Coordinated(msg, barrier)
  }

  def atomic[T](body: => T)(implicit factory: TransactionFactory = Coordinated.DefaultFactory): T =
    atomic(factory)(body)

  def atomic[T](factory: TransactionFactory)(body: => T): T = {
    factory.boilerplate.execute(new TransactionalCallable[T]() {
      def call(mtx: MultiverseTransaction): T = {
        factory.addHooks
        val result = body
        val timeout = factory.config.timeout
        try {
          barrier.tryJoinCommit(mtx, timeout.length, timeout.unit)
        } catch {
          // Need to catch IllegalStateException until we have fix in Multiverse, since it throws it by mistake
          case e: IllegalStateException => ()
        }
        result
      }
    })
  }
}
