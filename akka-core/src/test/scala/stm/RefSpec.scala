package se.scalablesolutions.akka.stm

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import se.scalablesolutions.akka.stm.local._

@RunWith(classOf[JUnitRunner])
class RefSpec extends Spec with ShouldMatchers {

  describe("A Ref") {


    it("should optionally accept an initial value") {
      val emptyRef = Ref[Int]
      val empty = atomic { emptyRef.get }

      empty should be(None)

      val ref = Ref(3)
      val value = atomic { ref.get.get }

      value should be(3)
    }

    it("should keep the initial value, even if the first transaction is rolled back") {
      val ref = Ref(3)

      try {
        atomic(DefaultLocalTransactionFactory) {
          ref.swap(5)
          throw new Exception
        }
      } catch {
        case e => {}
      }

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

    it("should be able to be mapped") {
      val ref1 = Ref(1)

      val ref2 = atomic {
        ref1 map (_ + 1)
      }

      val value1 = atomic { ref1.get.get }
      val value2 = atomic { ref2.get.get }

      value1 should be(1)
      value2 should be(2)
    }

    it("should be able to be used in a 'foreach' for comprehension") {
      val ref = Ref(3)

      var result = 0

      atomic {
        for (value <- ref) {
          result += value
        }
      }

      result should be(3)
    }

    it("should be able to be used in a 'map' for comprehension") {
      val ref1 = Ref(1)

      val ref2 = atomic {
        for (value <- ref1) yield value + 2
      }

      val value2 = atomic { ref2.get.get }

      value2 should be(3)
    }

    it("should be able to be used in a 'flatMap' for comprehension") {
      val ref1 = Ref(1)
      val ref2 = Ref(2)

      val ref3 = atomic {
        for {
          value1 <- ref1
          value2 <- ref2
        } yield value1 + value2
      }

      val value3 = atomic { ref3.get.get }

      value3 should be(3)
    }

    it("should be able to be used in a 'filter' for comprehension") {
      val ref1 = Ref(1)

      val refLess2 = atomic {
        for (value <- ref1 if value < 2) yield value
      }

      val optLess2 = atomic { refLess2.get }

      val refGreater2 = atomic {
        for (value <- ref1 if value > 2) yield value
      }

      val optGreater2 = atomic { refGreater2.get }

      optLess2 should be(Some(1))
      optGreater2 should be(None)
    }
  }
}
