package akka.docs.stm

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

class StmDocSpec extends WordSpec with MustMatchers {

  "simple counter example" in {
    //#simple
    import akka.stm._

    val ref = Ref(0)

    def counter = atomic {
      ref alter (_ + 1)
    }

    counter
    // -> 1

    counter
    // -> 2
    //#simple

    ref.get must be === 2
  }
}
