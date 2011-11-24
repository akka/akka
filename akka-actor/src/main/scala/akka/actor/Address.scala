/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

/**
 * The address specifies the physical location under which an Actor can be
 * reached. Examples are local addresses, identified by the ActorSystem’s
 * name, and remote addresses, identified by protocol, host and port.
 */
abstract class Address {
  def protocol: String
  def hostPort: String
  @transient
  override lazy val toString = protocol + "://" + hostPort
}

case class LocalAddress(systemName: String) extends Address {
  def protocol = "akka"
  def hostPort = systemName
}