/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor.{ Address, ActorSystem }
import akka.event.{ Logging, LogSource }
import akka.remote.testkit.MultiNodeConfig

/**
 * User controllable "puppet" failure detector.
 */
class FailureDetectorPuppet(system: ActorSystem, settings: ClusterSettings) extends FailureDetector {
  import java.util.concurrent.ConcurrentHashMap

  trait Status
  object Up extends Status
  object Down extends Status

  implicit private val logSource: LogSource[AnyRef] = new LogSource[AnyRef] {
    def genString(o: AnyRef): String = o.getClass.getName
    override def getClazz(o: AnyRef): Class[_] = o.getClass
  }

  private val log = Logging(system, this)

  private val connections = new ConcurrentHashMap[Address, Status]

  def markNodeAsUnavailable(connection: Address): this.type = {
    connections.put(connection, Down)
    this
  }

  def markNodeAsAvailable(connection: Address): this.type = {
    connections.put(connection, Up)
    this
  }

  def isAvailable(connection: Address): Boolean = connections.get(connection) match {
    case null | Up ⇒
      log.debug("Cluster node is available [{}]", connection)
      true
    case Down ⇒
      log.debug("Cluster node is unavailable [{}]", connection)
      false
  }

  override def isMonitoring(connection: Address): Boolean = connections.containsKey(connection)

  def heartbeat(connection: Address): Unit = {
    log.debug("Heart beat from cluster node[{}]", connection)
    connections.putIfAbsent(connection, Up)
  }

  def remove(connection: Address): Unit = {
    log.debug("Removing cluster node [{}]", connection)
    connections.remove(connection)
  }

  def reset(): Unit = {
    log.debug("Resetting failure detector")
    connections.clear()
  }
}

