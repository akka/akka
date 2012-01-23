/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

/**
 * Base trait for the events raised by a ZeroMQ socket actor
 */
sealed trait Response

/**
 * When the ZeroMQ socket connects it sends this message to a listener
 */
case object Connecting extends Response
/**
 * When the ZeroMQ socket disconnects it sends this message to a listener
 */
case object Closed extends Response
