package se.scalablesolutions.akka.persistence.riak

import org.scalatest.matchers.ShouldMatchers
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import se.scalablesolutions.akka.persistence.riak.RiakStorageBackend._
import se.scalablesolutions.akka.util.{Logging}
import collection.immutable.TreeSet
import scala.None
import org.scalatest.{Spec, FunSuite}
import com.trifork.riak.RiakClient

@RunWith(classOf[JUnitRunner])
class RiakStorageBackendTestIntegration extends Spec with ShouldMatchers  with Logging {


  describe("successfuly configuring the riak pb client"){
    it("should connect to riak, if riak is running"){
      val riakClient = new RiakClient("localhost");
      riakClient.listBuckets should not be (null)
    }
  }

}