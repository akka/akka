package se.scalablesolutions.akka.persistence.hbase

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.junit.Test
import org.apache.hadoop.hbase.HBaseClusterTestCase

@Test
class PersistenceTest extends HBaseClusterTestCase with Spec with BeforeAndAfterAll {

  override def beforeAll {
    super.setUp
  }

  @Test
  def testPersistence {}

  override def afterAll {
    super.tearDown
  }

}
