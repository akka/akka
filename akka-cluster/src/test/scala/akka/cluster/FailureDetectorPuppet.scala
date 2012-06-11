/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor.{ Address, ActorSystem }
import akka.event.{ Logging, LogSource }

/**
 * User controllable "puppet" failure detector.
 */
class FailureDetectorPuppet(system: ActorSystem, settings: ClusterSettings) extends FailureDetector {
  import java.util.concurrent.ConcurrentHashMap

  def this(system: ActorSystem) = this(system, new ClusterSettings(system.settings.config, system.name))

  trait Status
  object Up extends Status
  object Down extends Status

  implicit private val logSource: LogSource[AnyRef] = new LogSource[AnyRef] {
    def genString(o: AnyRef): String = o.getClass.getName
    override def getClazz(o: AnyRef): Class[_] = o.getClass
  }

  private val log = Logging(system, this)

  private val connections = new ConcurrentHashMap[Address, Status]

  def markAsDown(connection: Address): this.type = {
    connections.put(connection, Down)
    this
  }

  def markAsUp(connection: Address): this.type = {
    connections.put(connection, Up)
    this
  }

  def isAvailable(connection: Address): Boolean = connections.get(connection) match {
    case null ⇒
      log.debug("Adding cluster node [{}]", connection)
      connections.put(connection, Up)
      true
    case Up ⇒
      log.debug("isAvailable: Cluster node IS NOT available [{}]", connection)
      true
    case Down ⇒
      log.debug("isAvailable: Cluster node IS available [{}]", connection)
      false
  }

  def heartbeat(connection: Address): Unit = log.debug("Heart beat from cluster node[{}]", connection)

  def remove(connection: Address): Unit = {
    log.debug("Removing cluster node [{}]", connection)
    connections.remove(connection)
  }
}
