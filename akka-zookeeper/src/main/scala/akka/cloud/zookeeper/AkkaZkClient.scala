/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.cloud.zookeeper

import org.I0Itec.zkclient._
import org.I0Itec.zkclient.serialize._
import org.I0Itec.zkclient.exception._

class AkkaZkClient(zkServers: String,
  sessionTimeout: Int,
  connectionTimeout: Int,
  zkSerializer: ZkSerializer = new SerializableSerializer)
  extends ZkClient(zkServers, sessionTimeout, connectionTimeout, zkSerializer) {

  def connection: ZkConnection = _connection.asInstanceOf[ZkConnection]

  def reconnect() {
    getEventLock.lock
    try {
      _connection.close
      _connection.connect(this)
    } catch {
      case e: InterruptedException => throw new ZkInterruptedException(e)
    } finally {
      getEventLock.unlock
    }
  }
}
