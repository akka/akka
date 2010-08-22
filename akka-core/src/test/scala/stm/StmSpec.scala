package se.scalablesolutions.akka.stm

import se.scalablesolutions.akka.actor.{Actor, Transactor}
import Actor._

import org.multiverse.api.exceptions.ReadonlyException

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

class StmSpec extends WordSpec with MustMatchers {

  "Local STM" should {

    import se.scalablesolutions.akka.stm.local._

    "be able to do multiple consecutive atomic {..} statements" in {
      val ref = Ref(0)

      def increment = atomic {
        ref alter (_ + 1)
      }

      def total: Int = atomic {
        ref.getOrElse(0)
      }

      increment
      increment
      increment

      total must be (3)
    }

    "be able to do nested atomic {..} statements" in {
      val ref = Ref(0)

      def increment = atomic {
        ref alter (_ + 1)
      }

      def total: Int = atomic {
        ref.getOrElse(0)
      }

      atomic {
        increment
        increment
      }

      atomic {
        increment
        total must be (3)
      }
    }

    "roll back failing nested atomic {..} statements" in {
      val ref = Ref(0)

      def increment = atomic {
        ref alter (_ + 1)
      }

      def total: Int = atomic {
        ref.getOrElse(0)
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

      total must be (0)
    }

    "use the outer transaction settings by default" in {
      val readonlyFactory = TransactionFactory(readonly = true)
      val writableFactory = TransactionFactory(readonly = false)

      val ref = Ref(0)

      def writableOuter =
        atomic(writableFactory) {
          atomic(readonlyFactory) {
            ref alter (_ + 1)
          }
        }

      def readonlyOuter =
        atomic(readonlyFactory) {
          atomic(writableFactory) {
            ref alter (_ + 1)
          }
        }

      writableOuter must be (1)
      evaluating { readonlyOuter } must produce [ReadonlyException]
    }

    "allow propagation settings for nested transactions" in {
      val readonlyFactory = TransactionFactory(readonly = true)
      val writableRequiresNewFactory = TransactionFactory(readonly = false, propagation = Propagation.RequiresNew)

      val ref = Ref(0)

      def writableOuter =
        atomic(writableRequiresNewFactory) {
          atomic(readonlyFactory) {
            ref alter (_ + 1)
          }
        }

      def readonlyOuter =
        atomic(readonlyFactory) {
          atomic(writableRequiresNewFactory) {
            ref alter (_ + 1)
          }
        }

      writableOuter must be (1)
      readonlyOuter must be (2)
    }
  }

  "Global STM" should {
    "be able to initialize with atomic {..} block inside actor constructor" in {
      import GlobalTransactionVectorTestActor._
      try {
        val actor = actorOf[GlobalTransactionVectorTestActor].start
        actor !! Add(5)
        val size1 = (actor !! Size).as[Int].getOrElse(fail("Could not get Vector::size"))
        size1 must be (2)
        actor !! Add(2)
        val size2 = (actor !! Size).as[Int].getOrElse(fail("Could not get Vector::size"))
        size2 must be (3)
      } catch {
        case e =>
          e.printStackTrace
          fail(e.toString)
      }
    }
  }

/*
  "Transactor" should {
    "be able receive message sent with !! and pass it along to nested transactor with !! and receive reply; multiple times in a row" in {
      import GlobalTransactionVectorTestActor._
      val actor = actorOf[NestedTransactorLevelOneActor].start
      actor !! (Add(2), 10000)
      val size1 = (actor !! (Size, 10000)).as[Int].getOrElse(fail("Could not get size"))
      size1 must be (2)
      actor !! (Add(7), 10000)
      actor ! "HiLevelOne"
      val size2 = (actor !! (Size, 10000)).as[Int].getOrElse(fail("Could not get size"))
      size2 must be (7)
      actor !! (Add(0), 10000)
      actor ! "HiLevelTwo"
      val size3 = (actor !! (Size, 10000)).as[Int].getOrElse(fail("Could not get size"))
      size3 must be (0)
      actor !! (Add(3), 10000)
      val size4 = (actor !! (Size, 10000)).as[Int].getOrElse(fail("Could not get size"))
      size4 must be (3)
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
