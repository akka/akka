package akka.stm.test

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

class RefSpec extends WordSpec with MustMatchers {

  import akka.stm._

  "A Ref" should {

    "optionally accept an initial value" in {
      val emptyRef = Ref[Int]
      val empty = atomic { emptyRef.opt }

      empty must be(None)

      val ref = Ref(3)
      val value = atomic { ref.get }

      value must be (3)
    }

    "keep the initial value, even if the first transaction is rolled back" in {
      val ref = Ref(3)

      try {
        atomic(DefaultTransactionFactory) {
          ref.swap(5)
          throw new Exception
        }
      } catch {
        case e => {}
      }

      val value = atomic { ref.get }

      value must be (3)
    }

    "be settable using set" in {
      val ref = Ref[Int]

      atomic { ref.set(3) }

      val value = atomic { ref.get }

      value must be (3)
    }

    "be settable using swap" in {
      val ref = Ref[Int]

      atomic { ref.swap(3) }

      val value = atomic { ref.get }

      value must be (3)
    }

    "be changeable using alter" in {
      val ref = Ref(0)

      def increment = atomic {
        ref alter (_ + 1)
      }

      increment
      increment
      increment

      val value = atomic { ref.get }

      value must be (3)
    }

    "be able to be mapped" in {
      val ref1 = Ref(1)

      val ref2 = atomic {
        ref1 map (_ + 1)
      }

      val value1 = atomic { ref1.get }
      val value2 = atomic { ref2.get }

      value1 must be (1)
      value2 must be (2)
    }

    "be able to be used in a 'foreach' for comprehension" in {
      val ref = Ref(3)

      var result = 0

      atomic {
        for (value <- ref) {
          result += value
        }
      }

      result must be (3)
    }

    "be able to be used in a 'map' for comprehension" in {
      val ref1 = Ref(1)

      val ref2 = atomic {
        for (value <- ref1) yield value + 2
      }

      val value2 = atomic { ref2.get }

      value2 must be (3)
    }

    "be able to be used in a 'flatMap' for comprehension" in {
      val ref1 = Ref(1)
      val ref2 = Ref(2)

      val ref3 = atomic {
        for {
          value1 <- ref1
          value2 <- ref2
        } yield value1 + value2
      }

      val value3 = atomic { ref3.get }

      value3 must be (3)
    }

    "be able to be used in a 'filter' for comprehension" in {
      val ref1 = Ref(1)

      val refLess2 = atomic {
        for (value <- ref1 if value < 2) yield value
      }

      val optLess2 = atomic { refLess2.opt }

      val refGreater2 = atomic {
        for (value <- ref1 if value > 2) yield value
      }

      val optGreater2 = atomic { refGreater2.opt }

      optLess2 must be (Some(1))
      optGreater2 must be (None)
    }
  }
}
