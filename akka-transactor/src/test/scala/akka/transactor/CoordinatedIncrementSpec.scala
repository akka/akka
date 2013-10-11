/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor

import org.scalatest.BeforeAndAfterAll

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.stm._
import scala.collection.immutable
import akka.actor._
import akka.util.Timeout
import akka.testkit._
import akka.pattern.{ AskTimeoutException, ask }

object CoordinatedIncrement {

  val config = """
    akka {
      actor {
        default-dispatcher {
          executor = "thread-pool-executor"
          thread-pool-executor {
            core-pool-size-min = 5
            core-pool-size-max = 16
          }
        }
      }
    }
  """

  case class Increment(friends: immutable.Seq[ActorRef])
  case object GetCount

  class Counter(name: String) extends Actor {
    val count = Ref(0)

    def receive = {
      case coordinated @ Coordinated(Increment(friends)) ⇒ {
        if (friends.nonEmpty) {
          friends.head ! coordinated(Increment(friends.tail))
        }
        coordinated.atomic { implicit t ⇒
          count transform (_ + 1)
        }
      }

      case GetCount ⇒ sender ! count.single.get
    }
  }

  class ExpectedFailureException extends RuntimeException("Expected failure")

  class Failer extends Actor {

    def receive = {
      case coordinated @ Coordinated(Increment(friends)) ⇒ {
        coordinated.atomic { t ⇒
          throw new ExpectedFailureException
        }
      }
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class CoordinatedIncrementSpec extends AkkaSpec(CoordinatedIncrement.config) with BeforeAndAfterAll {
  import CoordinatedIncrement._

  val numCounters = 4

  def actorOfs = {
    def createCounter(i: Int) = system.actorOf(Props(classOf[Counter], "counter" + i))
    val counters = (1 to numCounters) map createCounter
    val failer = system.actorOf(Props[Failer])
    (counters, failer)
  }

  "Coordinated increment" should {
    implicit val timeout = Timeout(2.seconds.dilated)
    "increment all counters by one with successful transactions" in {
      val (counters, failer) = actorOfs
      val coordinated = Coordinated()
      counters(0) ! coordinated(Increment(counters.tail))
      coordinated.await
      for (counter ← counters) {
        Await.result((counter ? GetCount).mapTo[Int], remaining) must be === 1
      }
      counters foreach (system.stop(_))
      system.stop(failer)
    }

    "increment no counters with a failing transaction" in {
      val ignoreExceptions = Seq(
        EventFilter[ExpectedFailureException](),
        EventFilter[CoordinatedTransactionException](),
        EventFilter[AskTimeoutException]())
      filterEvents(ignoreExceptions) {
        val (counters, failer) = actorOfs
        val coordinated = Coordinated()
        counters(0) ! Coordinated(Increment(counters.tail :+ failer))
        coordinated.await
        for (counter ← counters) {
          Await.result(counter ? GetCount, remaining) must be === 0
        }
        counters foreach (system.stop(_))
        system.stop(failer)
      }
    }
  }
}
