/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll

trait MasterClusterTestNode extends WordSpec with MustMatchers with BeforeAndAfterAll {
  def testNodes: Int

  override def beforeAll() = {
    //    LocalCluster.startLocalCluster()
    onReady()
    ClusterTestNode.ready(getClass.getName)
  }

  def onReady() = {}

  override def afterAll() = {
    ClusterTestNode.waitForExits(getClass.getName, testNodes - 1)
    ClusterTestNode.cleanUp(getClass.getName)
    onShutdown()
    //    LocalCluster.shutdownLocalCluster()
  }

  def onShutdown() = {}
}

