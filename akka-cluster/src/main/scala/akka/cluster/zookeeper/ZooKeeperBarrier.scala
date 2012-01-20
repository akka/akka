/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.zookeeper

import akka.util.Duration
import akka.util.duration._

import org.I0Itec.zkclient._
import org.I0Itec.zkclient.exception._

import java.util.{ List ⇒ JList }
import java.util.concurrent.CountDownLatch

class BarrierTimeoutException(message: String) extends RuntimeException(message)

/**
 * Barrier based on Zookeeper barrier tutorial.
 */
object ZooKeeperBarrier {
  val BarriersNode = "/barriers"
  val DefaultTimeout = 60 seconds

  def apply(zkClient: ZkClient, name: String, node: String, count: Int) =
    new ZooKeeperBarrier(zkClient, name, node, count, DefaultTimeout)

  def apply(zkClient: ZkClient, name: String, node: String, count: Int, timeout: Duration) =
    new ZooKeeperBarrier(zkClient, name, node, count, timeout)

  def apply(zkClient: ZkClient, cluster: String, name: String, node: String, count: Int) =
    new ZooKeeperBarrier(zkClient, cluster + "-" + name, node, count, DefaultTimeout)

  def apply(zkClient: ZkClient, cluster: String, name: String, node: String, count: Int, timeout: Duration) =
    new ZooKeeperBarrier(zkClient, cluster + "-" + name, node, count, timeout)

  def ignore[E: Manifest](body: ⇒ Unit) {
    try {
      body
    } catch {
      case e if manifest[E].erasure.isAssignableFrom(e.getClass) ⇒ ()
    }
  }
}

/**
 * Barrier based on Zookeeper barrier tutorial.
 */
class ZooKeeperBarrier(zkClient: ZkClient, name: String, node: String, count: Int, timeout: Duration)
  extends IZkChildListener {

  import ZooKeeperBarrier.{ BarriersNode, ignore }

  val barrier = BarriersNode + "/" + name
  val entry = barrier + "/" + node
  val ready = barrier + "/ready"

  val exitBarrier = new CountDownLatch(1)

  ignore[ZkNodeExistsException](zkClient.createPersistent(BarriersNode))
  ignore[ZkNodeExistsException](zkClient.createPersistent(barrier))

  def apply(body: ⇒ Unit) {
    enter()
    body
    leave()
  }

  /**
   * An await does a enter/leave making this barrier a 'single' barrier instead of a double barrier.
   */
  def await() {
    enter()
    leave()
  }

  def enter() = {
    zkClient.createEphemeral(entry)
    if (zkClient.countChildren(barrier) >= count)
      ignore[ZkNodeExistsException](zkClient.createPersistent(ready))
    else
      zkClient.waitUntilExists(ready, timeout.unit, timeout.length)
    if (!zkClient.exists(ready)) {
      throw new BarrierTimeoutException("Timeout (%s) while waiting for entry barrier" format timeout)
    }
    zkClient.subscribeChildChanges(barrier, this)
  }

  def leave() {
    zkClient.delete(entry)
    exitBarrier.await(timeout.length, timeout.unit)
    if (zkClient.countChildren(barrier) > 0) {
      zkClient.unsubscribeChildChanges(barrier, this)
      throw new BarrierTimeoutException("Timeout (%s) while waiting for exit barrier" format timeout)
    }
    zkClient.unsubscribeChildChanges(barrier, this)
  }

  def handleChildChange(path: String, children: JList[String]) {
    if (children.size <= 1) {
      ignore[ZkNoNodeException](zkClient.delete(ready))
      exitBarrier.countDown()
    }
  }
}
