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

    it("should be able to initialize with atomic {..} block inside actor constructor") {
      import StmSpecVectorTestActor._
      try {
        val actor = actorOf[StmSpecVectorTestActor].start
        actor !! Add(5)
        val size1: Int = (actor !! Size).getOrElse(fail("Could not get Vector::size"))
        size1 should equal(2)
        actor !! Add(2)
        val size2: Int = (actor !! Size).getOrElse(fail("Could not get Vector::size"))
        size2 should equal(2)
      } catch {
        case e => fail(e.toString)
      }
    }
  }
}

object StmSpecVectorTestActor {
  case class Add(value: Int)
  case object Size
  case object Success
}
class StmSpecVectorTestActor extends Actor {
  import StmSpecVectorTestActor._
  import se.scalablesolutions.akka.stm.Transaction.Global

  private var vector: TransactionalVector[Int] = Global.atomic { TransactionalVector(1) }

  def receive = {
    case Add(value) => 
      Global.atomic { vector + value}
      self.reply(Success)

    case Size => 
      val size = Global.atomic { vector.size }
      self.reply(size)
  }
}
