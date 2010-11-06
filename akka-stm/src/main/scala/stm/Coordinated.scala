package akka.stm

import akka.config.Config

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
 * Coordinated transactions across actors.
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
