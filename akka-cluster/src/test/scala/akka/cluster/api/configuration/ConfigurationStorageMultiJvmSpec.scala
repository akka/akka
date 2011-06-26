/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.cluster.api.configuration

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll

import akka.cluster._
import Cluster._

object ConfigurationStorageMultiJvmSpec {
  var NrOfNodes = 2
}

class ConfigurationStorageMultiJvmNode1 extends WordSpec with MustMatchers with BeforeAndAfterAll {
  import ConfigurationStorageMultiJvmSpec._

  "A cluster" must {

    "be able to store, read and remove custom configuration data" in {

      barrier("start-node-1", NrOfNodes) {
        node.start()
      }

      barrier("start-node-2", NrOfNodes) {
      }

      barrier("store-config-data-node-1", NrOfNodes) {
        node.setConfigElement("key1", "value1".getBytes)
      }

      barrier("read-config-data-node-2", NrOfNodes) {
      }

      barrier("remove-config-data-node-2", NrOfNodes) {
      }

      barrier("try-read-config-data-node-1", NrOfNodes) {
        val option = node.getConfigElement("key1")
        option.isDefined must be(false)

        val elements = node.getConfigElementKeys
        elements.size must be(0)
      }

      node.shutdown()
    }
  }

  override def beforeAll() = {
    startLocalCluster()
  }

  override def afterAll() = {
    shutdownLocalCluster()
  }
}

class ConfigurationStorageMultiJvmNode2 extends WordSpec with MustMatchers {
  import ConfigurationStorageMultiJvmSpec._

  "A cluster" must {

    "be able to store, read and remove custom configuration data" in {

      barrier("start-node-1", NrOfNodes) {
      }

      barrier("start-node-2", NrOfNodes) {
        node.start()
      }

      barrier("store-config-data-node-1", NrOfNodes) {
      }

      barrier("read-config-data-node-2", NrOfNodes) {
        val option = node.getConfigElement("key1")
        option.isDefined must be(true)
        option.get must be("value1".getBytes)

        val elements = node.getConfigElementKeys
        elements.size must be(1)
        elements.head must be("key1")
      }

      barrier("remove-config-data-node-2", NrOfNodes) {
        node.removeConfigElement("key1")
      }

      barrier("try-read-config-data-node-1", NrOfNodes) {
      }

      node.shutdown()
    }
  }
}
