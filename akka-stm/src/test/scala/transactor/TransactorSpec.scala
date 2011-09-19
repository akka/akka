package akka.transactor.test

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.transactor.Transactor
import akka.actor.{ Actor, ActorRef, ActorTimeoutException }
import akka.stm._
import akka.util.duration._
import akka.event.EventHandler
import akka.transactor.CoordinatedTransactionException
import akka.testkit._

import java.util.concurrent.CountDownLatch

object TransactorIncrement {
  case class Increment(friends: Seq[ActorRef], latch: CountDownLatch)
  case object GetCount

  class Counter(name: String) extends Transactor {
    val count = Ref(0)

    override def transactionFactory = TransactionFactory(timeout = 3 seconds)

    def increment = {
      count alter (_ + 1)
    }

    override def coordinate = {
      case Increment(friends, latch) ⇒ {
        if (friends.nonEmpty) sendTo(friends.head -> Increment(friends.tail, latch))
        else nobody
      }
    }

    override def before = {
      case i: Increment ⇒
    }

    def atomically = {
      case Increment(friends, latch) ⇒ {
        increment
        deferred { latch.countDown() }
        compensating { latch.countDown() }
      }
    }

    override def after = {
      case i: Increment ⇒
    }

    override def normally = {
      case GetCount ⇒ reply(count.get)
    }
  }

  class ExpectedFailureException extends RuntimeException("Expected failure")

  class Failer extends Transactor {
    def atomically = {
      case _ ⇒ throw new ExpectedFailureException
    }
  }
}

object SimpleTransactor {
  case class Set(ref: Ref[Int], value: Int, latch: CountDownLatch)

  class Setter extends Transactor {
    def atomically = {
      case Set(ref, value, latch) ⇒ {
        ref.set(value)
        latch.countDown()
      }
    }
  }
}

class TransactorSpec extends WordSpec with MustMatchers {
  import TransactorIncrement._
  import SimpleTransactor._

  val numCounters = 5
  val timeout = 5 seconds

  def createTransactors = {
    def createCounter(i: Int) = Actor.actorOf(new Counter("counter" + i))
    val counters = (1 to numCounters) map createCounter
    val failer = Actor.actorOf(new Failer)
    (counters, failer)
  }

  "Transactor increment" should {
    "increment all counters by one with successful transactions" in {
      val (counters, failer) = createTransactors
      val incrementLatch = new CountDownLatch(numCounters)
      counters(0) ! Increment(counters.tail, incrementLatch)
      incrementLatch.await(timeout.length, timeout.unit)
      for (counter ← counters) {
        (counter ? GetCount).as[Int].get must be === 1
      }
      counters foreach (_.stop())
      failer.stop()
    }

    "increment no counters with a failing transaction" in {
      val ignoreExceptions = Seq(
        EventFilter[ExpectedFailureException],
        EventFilter[CoordinatedTransactionException],
        EventFilter[ActorTimeoutException])
      EventHandler.notify(TestEvent.Mute(ignoreExceptions))
      val (counters, failer) = createTransactors
      val failLatch = new CountDownLatch(numCounters + 1)
      counters(0) ! Increment(counters.tail :+ failer, failLatch)
      failLatch.await(timeout.length, timeout.unit)
      for (counter ← counters) {
        (counter ? GetCount).as[Int].get must be === 0
      }
      counters foreach (_.stop())
      failer.stop()
      EventHandler.notify(TestEvent.UnMute(ignoreExceptions))
    }
  }

  "Transactor" should {
    "be usable without overriding normally" in {
      val transactor = Actor.actorOf(new Setter)
      val ref = Ref(0)
      val latch = new CountDownLatch(1)
      transactor ! Set(ref, 5, latch)
      latch.await(timeout.length, timeout.unit)
      val value = atomic { ref.get }
      value must be === 5
      transactor.stop()
    }
  }
}
