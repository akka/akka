/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.testkit

import org.scalatest.exceptions.TestFailedException
import org.scalatest.{ BeforeAndAfterAll, Suite }

//# source-quote
trait TestFrameworkInterface {

  def cleanUp()

  def failTest(msg: String): Nothing
}
//#

object TestFrameworkInterface {

  trait Scalatest extends TestFrameworkInterface with BeforeAndAfterAll {
    this: Suite ⇒

    def failTest(msg: String) = throw new TestFailedException(msg, 11)

    abstract override protected def afterAll(): Unit = {
      cleanUp()
      super.afterAll()
    }
  }

}
