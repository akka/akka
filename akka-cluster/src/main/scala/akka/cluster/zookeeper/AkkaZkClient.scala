/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.cluster.zookeeper

import org.I0Itec.zkclient._
import org.I0Itec.zkclient.serialize._
import org.I0Itec.zkclient.exception._

//import akka.event.EventHandler

/**
 * ZooKeeper client. Holds the ZooKeeper connection and manages its session.
 */
class AkkaZkClient(zkServers: String,
                   sessionTimeout: Int,
                   connectionTimeout: Int,
                   zkSerializer: ZkSerializer = new SerializableSerializer)
  extends ZkClient(zkServers, sessionTimeout, connectionTimeout, zkSerializer) {

  //  EventHandler.debug(this, "Connecting to ZooKeeper ensamble [%s]" format zkServers)

  def connection: ZkConnection = _connection.asInstanceOf[ZkConnection]

  def reconnect() {
    val zkLock = getEventLock

    zkLock.lock()
    try {
      _connection.close()
      _connection.connect(this)
    } catch {
      case e: InterruptedException ⇒ throw new ZkInterruptedException(e)
    }
    finally {
      zkLock.unlock()
    }
  }
}
