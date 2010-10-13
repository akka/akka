package se.scalablesolutions.akka.persistence.riak

import org.scalatest.matchers.ShouldMatchers
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import se.scalablesolutions.akka.persistence.riak.RiakStorageBackend._
import se.scalablesolutions.akka.util.{Logging}
import collection.immutable.TreeSet
import scala.None
import org.scalatest.{Spec, FunSuite}
import com.trifork.riak.{RiakObject, RiakClient}
import collection.JavaConversions

@RunWith(classOf[JUnitRunner])
class RiakStorageBackendTestIntegration extends Spec with ShouldMatchers  with Logging {

  import se.scalablesolutions.akka.persistence.riak.RiakStorageBackend.RiakAccess._

  describe("successfuly configuring the riak pb client"){
    it("should connect to riak, if riak is running"){
      val riakClient = new RiakClient("localhost");
      val props = riakClient.getServerInfo
      JavaConversions.asMap(props) foreach {
        _ match {
          case (k,v) => log.info("%s -> %s",k,v)
        }
      }
      val maps = riakClient.getBucketProperties("Maps")
      debug(maps)
      riakClient.listBuckets should not be (null)
      riakClient.store(new RiakObject("Maps", "testkey", "testvalue"))
      riakClient.fetch("Maps", "testkey").isEmpty should be (false)
      riakClient.fetch("Maps", "testkey")(0).getValue.toStringUtf8 should be("testvalue")
      //riakClient.delete("Maps","testkey")
      RiakStorageBackend.MapClient.delete("testkey")
      RiakStorageBackend.VectorClient.quorum
      riakClient.fetch("Maps", "testkey").isEmpty should be (true)
      riakClient.store(new RiakObject("Maps", "testkey", "testvalue"))
      riakClient.fetch("Maps", "testkey").isEmpty should be (false)
      riakClient.fetch("Maps", "testkey")(0).getValue.toStringUtf8 should be("testvalue")
      //riakClient.delete("Maps","testkey")
      RiakStorageBackend.MapClient.delete("testkey")

      riakClient.fetch("Maps", "testkey").isEmpty should be (true)
    }
  }


  def debug(ani:Any){
    log.debug("ani")
  }

}