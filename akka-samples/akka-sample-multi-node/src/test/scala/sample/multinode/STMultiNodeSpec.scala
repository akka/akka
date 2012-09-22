/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
//#example
package sample.multinode

//#imports
import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers
import akka.remote.testkit.MultiNodeSpecCallbacks
//#imports

//#trait
/**
 * Hooks up MultiNodeSpec with ScalaTest
 */
trait STMultiNodeSpec extends MultiNodeSpecCallbacks
  with WordSpec with MustMatchers with BeforeAndAfterAll {

  override def beforeAll() = multiNodeSpecBeforeAll()

  override def afterAll() = multiNodeSpecAfterAll()
}
//#trait
//#example
