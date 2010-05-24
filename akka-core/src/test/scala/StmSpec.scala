package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.stm._

import Actor._

import org.scalatest.Spec
import org.scalatest.Assertions
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

@RunWith(classOf[JUnitRunner])
class StmSpec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterAll {

  describe("Transaction.Local") {
    it("should be able to do multiple consecutive atomic {..} statements") {
      import Transaction.Local._

      lazy val ref = TransactionalState.newRef[Int]

      def increment = atomic {
        ref.swap(ref.get.getOrElse(0) + 1)
      }

      def total: Int = atomic {
        ref.get.getOrElse(0)
      }

      increment
      increment
      increment
      total should equal(3)
    }

    it("should be able to do nested atomic {..} statements") {
      import Transaction.Local._

      lazy val ref = TransactionalState.newRef[Int]

      def increment = atomic {
        ref.swap(ref.get.getOrElse(0) + 1)
      }
      def total: Int = atomic {
        ref.get.getOrElse(0)
      }

      atomic {
        increment
        increment
      }
      atomic {
        increment
        total should equal(3)
      }
    }

    it("should roll back failing nested atomic {..} statements") {
      import Transaction.Local._

      lazy val ref = TransactionalState.newRef[Int]

      def increment = atomic {
        ref.swap(ref.get.getOrElse(0) + 1)
      }
      def total: Int = atomic {
        ref.get.getOrElse(0)
      }
      try {
        atomic {
          increment
          increment
          throw new Exception
        }
      } catch {
        case e => {}
      }
      total should equal(0)
    }

    it("should be able to initialize with atomic block inside actor constructor") {
      try {
        val actor = actorOf[StmTestActor]
      } catch {
        case e => fail(e.toString)
      }
    }
  }
}

class StmTestActor extends Actor {
  import se.scalablesolutions.akka.persistence.redis.RedisStorage
  import se.scalablesolutions.akka.stm.Transaction.Global
  private var eventLog = Global.atomic { RedisStorage.getVector("log") }

  def receive = { case _ => () }
    /*
    case msg @ EnrichTrade(trade) => 
      atomic { eventLog + msg.toString.getBytes("UTF-8") }

    case msg @ ValueTrade(trade) => 
      atomic { eventLog + msg.toString.getBytes("UTF-8") }

    case GetEventLog(trade) => 
      val eventList = atomic { eventLog.map(bytes => new String(bytes, "UTF-8")).toList }
      reply(EventLog(eventList))
  }
  */
}
