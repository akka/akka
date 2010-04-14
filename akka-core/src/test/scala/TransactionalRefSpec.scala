package se.scalablesolutions.akka.stm

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

@RunWith(classOf[JUnitRunner])
class TransactionalRefSpec extends Spec with ShouldMatchers {

  describe("A TransactionalRef") {
    import Transaction.Local._

    it("should optionally accept an initial value") {
      val emptyRef = Ref[Int]
      val empty = atomic { emptyRef.get }

      empty should be(None)

      val ref = Ref(3)
      val value = atomic { ref.get.get }

      value should be(3)
    }

    it("should be settable using swap") {
      val ref = Ref[Int]

      atomic { ref.swap(3) }

      val value = atomic { ref.get.get }

      value should be(3)
    }

    it("should be changeable using alter") {
      val ref = Ref(0)

      def increment = atomic {
	ref alter (_ + 1)
      }

      increment
      increment
      increment

      val value = atomic { ref.get.get }

      value should be(3)
    }

    it("should not be changeable using alter if no value has been set") {
      val ref = Ref[Int]

      def increment = atomic {
	ref alter (_ + 1)
      }

      evaluating { increment } should produce [RuntimeException]
    }

    it("should be able to be mapped") (pending)
    it("should be able to be used in a 'foreach' for comprehension") (pending)
    it("should be able to be used in a 'map' for comprehension") (pending)
    it("should be able to be used in a 'flatMap' for comprehension") (pending)
    it("should be able to be used in a 'filter' for comprehension") (pending)
  }
}
