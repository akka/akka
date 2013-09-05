/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
//#example
package sample.multinode

//#imports
import org.scalatest.{ BeforeAndAfterAll, WordSpecLike }
import org.scalatest.matchers.MustMatchers
import akka.remote.testkit.MultiNodeSpecCallbacks
//#imports

//#trait
/**
 * Hooks up MultiNodeSpec with ScalaTest
 */
trait STMultiNodeSpec extends MultiNodeSpecCallbacks
  with WordSpecLike with MustMatchers with BeforeAndAfterAll {

  override def beforeAll() = multiNodeSpecBeforeAll()

  override def afterAll() = multiNodeSpecAfterAll()
}
//#trait
//#example
