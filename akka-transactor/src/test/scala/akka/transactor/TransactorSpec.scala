/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor

import akka.actor._
import akka.dispatch.Await
import akka.util.duration._
import akka.util.Timeout
import akka.testkit._
import scala.concurrent.stm._

object TransactorIncrement {
  case class Increment(friends: Seq[ActorRef], latch: TestLatch)
  case object GetCount

  class Counter(name: String) extends Transactor {
    val count = Ref(0)

    def increment(implicit txn: InTxn) = {
      count transform (_ + 1)
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

    def atomically = implicit txn ⇒ {
      case Increment(friends, latch) ⇒ {
        increment
        Txn.afterCompletion { status ⇒ latch.countDown() }
      }
    }

    override def after = {
      case i: Increment ⇒
    }

    override def normally = {
      case GetCount ⇒ sender ! count.single.get
    }
  }

  class ExpectedFailureException extends RuntimeException("Expected failure")

  class Failer extends Transactor {
    def atomically = implicit txn ⇒ {
      case _ ⇒ throw new ExpectedFailureException
    }
  }
}

object SimpleTransactor {
  case class Set(ref: Ref[Int], value: Int, latch: TestLatch)

  class Setter extends Transactor {
    def atomically = implicit txn ⇒ {
      case Set(ref, value, latch) ⇒ {
        ref() = value
        Txn.afterCompletion { status ⇒ latch.countDown() }
      }
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TransactorSpec extends AkkaSpec {
  import TransactorIncrement._
  import SimpleTransactor._

  implicit val timeout = Timeout(5.seconds.dilated)

  val numCounters = 3

  def createTransactors = {
    def createCounter(i: Int) = system.actorOf(Props(new Counter("counter" + i)))
    val counters = (1 to numCounters) map createCounter
    val failer = system.actorOf(Props(new Failer))
    (counters, failer)
  }

  "Transactor increment" should {
    "increment all counters by one with successful transactions" in {
      val (counters, failer) = createTransactors
      val incrementLatch = TestLatch(numCounters)
      counters(0) ! Increment(counters.tail, incrementLatch)
      Await.ready(incrementLatch, 5 seconds)
      for (counter ← counters) {
        Await.result(counter ? GetCount, timeout.duration) must be === 1
      }
      counters foreach (system.stop(_))
      system.stop(failer)
    }

    "increment no counters with a failing transaction" in {
      val ignoreExceptions = Seq(
        EventFilter[ExpectedFailureException](),
        EventFilter[CoordinatedTransactionException](),
        EventFilter[ActorTimeoutException]())
      filterEvents(ignoreExceptions) {
        val (counters, failer) = createTransactors
        val failLatch = TestLatch(numCounters)
        counters(0) ! Increment(counters.tail :+ failer, failLatch)
        Await.ready(failLatch, 5 seconds)
        for (counter ← counters) {
          Await.result(counter ? GetCount, timeout.duration) must be === 0
        }
        counters foreach (system.stop(_))
        system.stop(failer)
      }
    }
  }

  "Transactor" should {
    "be usable without overriding normally" in {
      val transactor = system.actorOf(Props(new Setter))
      val ref = Ref(0)
      val latch = TestLatch(1)
      transactor ! Set(ref, 5, latch)
      Await.ready(latch, 5 seconds)
      val value = ref.single.get
      value must be === 5
      system.stop(transactor)
    }
  }
}
