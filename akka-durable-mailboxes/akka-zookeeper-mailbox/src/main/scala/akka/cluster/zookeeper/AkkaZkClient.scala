/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.zookeeper

import org.I0Itec.zkclient._
import org.I0Itec.zkclient.serialize._
import org.I0Itec.zkclient.exception._
import akka.util.Duration

/**
 * ZooKeeper client. Holds the ZooKeeper connection and manages its session.
 */
@deprecated("ZooKeeperBasedMailbox will be removed in Akka 2.1", "2.0.2")
class AkkaZkClient(zkServers: String,
                   sessionTimeout: Duration,
                   connectionTimeout: Duration,
                   zkSerializer: ZkSerializer = new SerializableSerializer)
  extends ZkClient(zkServers, sessionTimeout.toMillis.toInt, connectionTimeout.toMillis.toInt, zkSerializer) {

  def connection: ZkConnection = _connection.asInstanceOf[ZkConnection]

  def reconnect() {
    val zkLock = getEventLock

    zkLock.lock()
    try {
      _connection.close()
      _connection.connect(this)
    } catch {
      case e: InterruptedException ⇒ throw new ZkInterruptedException(e)
    } finally {
      zkLock.unlock()
    }
  }
}
