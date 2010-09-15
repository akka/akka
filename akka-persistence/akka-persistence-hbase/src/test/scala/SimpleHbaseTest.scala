package se.scalablesolutions.akka.persistence.hbase

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.junit.Test

import org.apache.hadoop.hbase.HBaseTestingUtility

@RunWith(classOf[JUnitRunner])
class PersistenceSpec extends Spec with BeforeAndAfterAll with ShouldMatchers {

  import org.apache.hadoop.hbase.HBaseTestingUtility

  val testUtil = new HBaseTestingUtility

  override def beforeAll {
    testUtil.startMiniCluster
  }

  override def afterAll {
    testUtil.shutdownMiniCluster
  }

  describe("simple hbase persistence test") {
    it("should create a table") {
      import org.apache.hadoop.hbase.util.Bytes
      import org.apache.hadoop.hbase.HTableDescriptor
      import org.apache.hadoop.hbase.HColumnDescriptor
      import org.apache.hadoop.hbase.client.HBaseAdmin
      import org.apache.hadoop.hbase.client.HTable

      val descriptor = new HTableDescriptor(Bytes.toBytes("ATable"))
      descriptor.addFamily(new HColumnDescriptor(Bytes.toBytes("Family1")))
      descriptor.addFamily(new HColumnDescriptor(Bytes.toBytes("Family2")))
      val admin = new HBaseAdmin(testUtil.getConfiguration)
      admin.createTable(descriptor)
      val table = new HTable(testUtil.getConfiguration, Bytes.toBytes("ATable"))
 
      table should not equal(null)
    }

  }

}
