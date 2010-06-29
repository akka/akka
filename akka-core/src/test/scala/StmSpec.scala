package se.scalablesolutions.akka.stm

import se.scalablesolutions.akka.actor.{Actor, Transactor}
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

  describe("Local STM") {
    it("should be able to do multiple consecutive atomic {..} statements") {
      import local._

      lazy val ref = Ref[Int]()

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
      import local._

      lazy val ref = Ref[Int]()

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
      import local._

      lazy val ref = Ref[Int]()

      def increment = atomic {
        ref.swap(ref.get.getOrElse(0) + 1)
      }
      def total: Int = atomic {
        ref.get.getOrElse(0)
      }
      try {
        atomic(DefaultLocalTransactionFactory) {
          increment
          increment
          throw new Exception
        }
      } catch {
        case e => {}
      }
      total should equal(0)
    }
  }

  describe("Global STM") {
    it("should be able to initialize with atomic {..} block inside actor constructor") {
      import GlobalTransactionVectorTestActor._
      try {
        val actor = actorOf[GlobalTransactionVectorTestActor].start
        actor !! Add(5)
        val size1 = (actor !! Size).as[Int].getOrElse(fail("Could not get Vector::size"))
        size1 should equal(2)
        actor !! Add(2)
        val size2 = (actor !! Size).as[Int].getOrElse(fail("Could not get Vector::size"))
        size2 should equal(3)
      } catch {
        case e =>
          e.printStackTrace
          fail(e.toString)
      }
    }
  }
/*
  describe("Transactor") {
    it("should be able receive message sent with !! and pass it along to nested transactor with !! and receive reply; multiple times in a row") {
      import GlobalTransactionVectorTestActor._
      val actor = actorOf[NestedTransactorLevelOneActor].start
      actor !! (Add(2), 10000)
      val size1 = (actor !! (Size, 10000)).as[Int].getOrElse(fail("Could not get size"))
      size1 should equal(2)
      actor !! (Add(7), 10000)
      actor ! "HiLevelOne"
      val size2 = (actor !! (Size, 10000)).as[Int].getOrElse(fail("Could not get size"))
      size2 should equal(7)
      actor !! (Add(0), 10000)
      actor ! "HiLevelTwo"
      val size3 = (actor !! (Size, 10000)).as[Int].getOrElse(fail("Could not get size"))
      size3 should equal(0)
      actor !! (Add(3), 10000)
      val size4 = (actor !! (Size, 10000)).as[Int].getOrElse(fail("Could not get size"))
      size4 should equal(3)
    }
  }
  */
}

object GlobalTransactionVectorTestActor {
  case class Add(value: Int)
  case object Size
  case object Success
}
class GlobalTransactionVectorTestActor extends Actor {
  import GlobalTransactionVectorTestActor._
  import se.scalablesolutions.akka.stm.global._

  private val vector: TransactionalVector[Int] = atomic { TransactionalVector(1) }

  def receive = {
    case Add(value) =>
      atomic { vector + value}
      self.reply(Success)

    case Size =>
      val size = atomic { vector.size }
      self.reply(size)
  }
}

class NestedTransactorLevelOneActor extends Actor {
  import GlobalTransactionVectorTestActor._
  private val nested = actorOf[NestedTransactorLevelTwoActor].start
  self.timeout = 10000

  def receive = {
    case add @ Add(_) =>
      self.reply((nested !! add).get)

    case Size =>
      self.reply((nested !! Size).get)

    case "HiLevelOne" => println("HiLevelOne")
    case "HiLevelTwo" => nested ! "HiLevelTwo"
  }
}

class NestedTransactorLevelTwoActor extends Transactor {
  import GlobalTransactionVectorTestActor._
  private val ref = Ref(0)
  self.timeout = 10000

  def receive = {
    case Add(value) =>
      ref.swap(value)
      self.reply(Success)

    case Size =>
      self.reply(ref.getOrElse(-1))

    case "HiLevelTwo" => println("HiLevelTwo")
  }
}
