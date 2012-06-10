/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor.{ Address, ActorSystem }
import akka.event.{ Logging, LogSource }

/**
 * Interface for Akka failure detectors.
 */
trait FailureDetector {

  /**
   * Returns true if the connection is considered to be up and healthy
   * and returns false otherwise.
   */
  def isAvailable(connection: Address): Boolean

  /**
   * Records a heartbeat for a connection.
   */
  def heartbeat(connection: Address): Unit

  /**
   * Calculates how likely it is that the connection has failed.
   * <p/>
   * If a connection does not have any records in failure detector then it is
   * considered healthy.
   */
  def phi(connection: Address): Double

  /**
   * Removes the heartbeat management for a connection.
   */
  def remove(connection: Address): Unit
}

/**
 * User controllable "puppet" failure detector.
 */
class FailureDetectorPuppet(system: ActorSystem, connectionsToStartWith: Address*) extends FailureDetector {
  import java.util.concurrent.ConcurrentHashMap

  trait Status
  object Up extends Status
  object Down extends Status

  implicit val logSource: LogSource[AnyRef] = new LogSource[AnyRef] {
    def genString(o: AnyRef): String = o.getClass.getName
    override def getClazz(o: AnyRef): Class[_] = o.getClass
  }

  val log = Logging(system, this)

  private val connections = {
    val cs = new ConcurrentHashMap[Address, Status]
    connectionsToStartWith foreach { cs put (_, Up) }
    cs
  }

  def +(connection: Address): this.type = {
    log.debug("Adding cluster node [{}]", connection)
    connections.put(connection, Up)
    this
  }

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
      this + connection
      true
    case Up ⇒
      log.debug("isAvailable: Cluster node IS NOT available [{}]", connection)
      true
    case Down ⇒
      log.debug("isAvailable: Cluster node IS available [{}]", connection)
      false
  }

  def heartbeat(connection: Address): Unit = log.debug("Heart beat from cluster node[{}]", connection)

  def phi(connection: Address): Double = 0.1D

  def remove(connection: Address): Unit = {
    log.debug("Removing cluster node [{}]", connection)
    connections.remove(connection)
  }
}
