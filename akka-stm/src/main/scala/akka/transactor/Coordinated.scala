/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.transactor

import akka.AkkaException
import akka.config.Config
import akka.stm.{ Atomic, DefaultTransactionConfig, TransactionFactory }

import org.multiverse.commitbarriers.CountDownCommitBarrier
import org.multiverse.templates.TransactionalCallable
import akka.actor.ActorTimeoutException
import org.multiverse.api.{ TransactionConfiguration, Transaction ⇒ MultiverseTransaction }
import org.multiverse.api.exceptions.ControlFlowError

/**
 * Akka-specific exception for coordinated transactions.
 */
class CoordinatedTransactionException(message: String, cause: Throwable = null) extends AkkaException(message, cause){
  def this(message: String) = this(message, null)
}

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

  // Java API constructors
  def this(message: Any) = this(message, Coordinated.createBarrier)
  def this() = this(null, Coordinated.createBarrier)

  /**
   * Create a new Coordinated object and increment the number of parties by one.
   * Use this method to ''pass on'' the coordination.
   */
  def apply(msg: Any) = {
    barrier.incParties(1)
    new Coordinated(msg, barrier)
  }

  /**
   * Create a new Coordinated object but *do not* increment the number of parties by one.
   * Only use this method if you know this is what you need.
   */
  def noIncrement(msg: Any) = new Coordinated(msg, barrier)

  /**
   * Java API: get the message for this Coordinated.
   */
  def getMessage() = message

  /**
   * Java API: create a new Coordinated object and increment the number of parties by one.
   * Use this method to ''pass on'' the coordination.
   */
  def coordinate(msg: Any) = apply(msg)

  /**
   * Delimits the coordinated transaction. The transaction will wait for all other transactions
   * in this coordination before committing. The timeout is specified by the transaction factory.
   */
  def atomic[T](body: ⇒ T)(implicit factory: TransactionFactory = Coordinated.DefaultFactory): T =
    atomic(factory)(body)

  /**
   * Delimits the coordinated transaction. The transaction will wait for all other transactions
   * in this coordination before committing. The timeout is specified by the transaction factory.
   */
  def atomic[T](factory: TransactionFactory)(body: ⇒ T): T = {
    factory.boilerplate.execute(new TransactionalCallable[T]() {
      def call(mtx: MultiverseTransaction): T = {
        val result = try {
          body
        } catch {
          case e: ControlFlowError ⇒ throw e
          case e: Exception ⇒ {
            barrier.abort()
            throw e
          }
        }

        val timeout = factory.config.timeout
        val success = try {
          barrier.tryJoinCommit(mtx, timeout.length, timeout.unit)
        } catch {
          case e: IllegalStateException ⇒ {
            val config: TransactionConfiguration = mtx.getConfiguration
            throw new CoordinatedTransactionException("Coordinated transaction [" + config.getFamilyName + "] aborted", e)
          }
        }

        if (!success) {
          val config: TransactionConfiguration = mtx.getConfiguration
          throw new ActorTimeoutException(
            "Failed to complete coordinated transaction [" + config.getFamilyName + "] " +
              "with a maxium timeout of [" + config.getTimeoutNs + "] ns")
        }
        result
      }
    })
  }

  /**
   * Java API: coordinated atomic method that accepts an [[akka.stm.Atomic]].
   * Delimits the coordinated transaction. The transaction will wait for all other transactions
   * in this coordination before committing. The timeout is specified by the transaction factory.
   */
  def atomic[T](jatomic: Atomic[T]): T = atomic(jatomic.factory)(jatomic.atomically)

  /**
   * Java API: coordinated atomic method that accepts an [[akka.transactor.Atomically]].
   * Delimits the coordinated transaction. The transaction will wait for all other transactions
   * in this coordination before committing. The timeout is specified by the transaction factory.
   */
  def atomic(atomically: Atomically): Unit = atomic(atomically.factory)(atomically.atomically)

  /**
   * An empty coordinated atomic block. Can be used to complete the number of parties involved
   * and wait for all transactions to complete. The default timeout is used.
   */
  def await() = atomic(Coordinated.DefaultFactory) {}
}
