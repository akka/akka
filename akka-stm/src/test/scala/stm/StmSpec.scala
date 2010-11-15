package akka.stm.test

import akka.actor.Actor
import Actor._

import org.multiverse.api.exceptions.ReadonlyException

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

class StmSpec extends WordSpec with MustMatchers {

  import akka.stm._

  "Local STM" should {

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
        atomic(DefaultTransactionFactory) {
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
}
