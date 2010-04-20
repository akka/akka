package se.scalablesolutions.akka.stm

import org.scalatest.FunSuite
import Transaction.Global._

class TransactionalVectorBugTestSuite extends FunSuite {

  test("adding more than 32 items to a Vector shouldn't blow it up") {
    atomic {
      var v1 = new Vector[Int]()
      for (i <- 0 to 31) {
        v1 = v1 + i
      }
      v1 = v1 + 32
    }
  }
}
