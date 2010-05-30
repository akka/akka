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
  }

  describe("Transaction.Global") {
    it("should be able to initialize with atomic {..} block inside actor constructor") {
      import GlobalTransactionVectorTestActor._
      try {
        val actor = actorOf[GlobalTransactionVectorTestActor].start
        actor !! Add(5)
        val size1: Int = (actor !! Size).getOrElse(fail("Could not get Vector::size"))
        size1 should equal(2)
        actor !! Add(2)
        val size2: Int = (actor !! Size).getOrElse(fail("Could not get Vector::size"))
        size2 should equal(3)
      } catch {
        case e => 
          e.printStackTrace
          fail(e.toString)
      }
    }
  }

  describe("Transactor") {
    it("should be able receive message sent with !! and pass it along to nested transactor with !! and receive reply; multipse times in a row") {
      import GlobalTransactionVectorTestActor._
      try {
        val actor = actorOf[NestedTransactorLevelOneActor].start
        actor !! Add(2)
        val size1: Int = (actor !! Size).getOrElse(fail("Could not get size"))
        size1 should equal(2)
        actor !! Add(7)
        actor ! "HiLevelOne"
        val size2: Int = (actor !! Size).getOrElse(fail("Could not get size"))
        size2 should equal(7)
        actor !! Add(0)
        actor ! "HiLevelTwo"
        val size3: Int = (actor !! Size).getOrElse(fail("Could not get size"))
        size3 should equal(0)
        actor !! Add(3)
        val size4: Int = (actor !! Size).getOrElse(fail("Could not get size"))
        size4 should equal(3)
      } catch {
        case e => 
          fail(e.toString)
      }
    }
  }
  /*
  describe("Multiverse API") {
    it("should blablabla") {
      
      import org.multiverse.api.programmatic._
//      import org.multiverse.api._
      import org.multiverse.templates._
      import java.util.concurrent.atomic._
      import se.scalablesolutions.akka.stm.Ref
      import org.multiverse.api.{GlobalStmInstance, ThreadLocalTransaction, Transaction => MultiverseTransaction}
      import org.multiverse.api.lifecycle.{TransactionLifecycleListener, TransactionLifecycleEvent}
      import org.multiverse.commitbarriers._
      
      def createRef[T]: ProgrammaticReference[T] = GlobalStmInstance
        .getGlobalStmInstance
        .getProgrammaticReferenceFactoryBuilder
        .build
        .atomicCreateReference(null.asInstanceOf[T])
      
      val ref1 = Ref(0)//createRef[Int]
      val ref2 = Ref(0)//createRef[Int]

      val committedCount = new AtomicInteger
      val abortedCount = new AtomicInteger
      val barrierHolder = new AtomicReference[CountDownCommitBarrier]

      val template = new TransactionTemplate[Int]() {
        override def onStart(tx: MultiverseTransaction) = barrierHolder.set(new CountDownCommitBarrier(1))
        override def execute(tx: MultiverseTransaction): Int = {
          ref1.swap(ref1.get.get + 1)
          ref2.swap(ref2.get.get + 1)
          barrierHolder.get.joinCommit(tx)
          null.asInstanceOf[Int]
        }
        override def onPostCommit = committedCount.incrementAndGet
        override def onPostAbort = abortedCount.incrementAndGet
      }
      template.execute

      ref1.get.get should equal(1)
      ref2.get.get should equal(1)
      committedCount.get should equal(1)
      abortedCount.get should equal(2)
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
  import se.scalablesolutions.akka.stm.Transaction.Global

  private val vector: TransactionalVector[Int] = Global.atomic { TransactionalVector(1) }
  
  def receive = {
    case Add(value) => 
      Global.atomic { vector + value}
      self.reply(Success)

    case Size => 
      val size = Global.atomic { vector.size }
      self.reply(size)
  }
}

class NestedTransactorLevelOneActor extends Actor {
  import GlobalTransactionVectorTestActor._
  private val nested = actorOf[NestedTransactorLevelTwoActor].start
  
  def receive = {
    case add @ Add(_) => 
      self.reply((nested !! add).get)

    case Size => 
      self.reply((nested !! Size).get)

    case "HiLevelOne" => println("HiLevelOne")
    case "HiLevelTwo" => nested ! "HiLevelTwo"
  }
}

class NestedTransactorLevelTwoActor extends Actor {
  import GlobalTransactionVectorTestActor._
  private val ref = Ref(0)
  
  def receive = {
    case Add(value) => 
      ref.swap(value)
      self.reply(Success)

    case Size => 
      self.reply(ref.getOrElse(-1))
      
    case "HiLevelTwo" => println("HiLevelTwo")
  }
}
