/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.testconductor

import akka.dispatch.Future

trait BarrierSync {
  /**
   * Enter all given barriers in the order in which they were given.
   */
  def enter(name: String*): Unit
}

sealed trait Direction

object Direction {
  case object Send extends Direction
  case object Receive extends Direction
  case object Both extends Direction
}

trait FailureInject {

  /**
   * Make the remoting pipeline on the node throttle data sent to or received
   * from the given remote peer.
   */
  def throttle(node: String, target: String, direction: Direction, rateMBit: Double): Future[Done]

  /**
   * Switch the Netty pipeline of the remote support into blackhole mode for
   * sending and/or receiving: it will just drop all messages right before
   * submitting them to the Socket or right after receiving them from the
   * Socket.
   */
  def blackhole(node: String, target: String, direction: Direction): Future[Done]

  /**
   * Tell the remote support to shutdown the connection to the given remote
   * peer. It works regardless of whether the recipient was initiator or
   * responder.
   */
  def disconnect(node: String, target: String): Future[Done]

  /**
   * Tell the remote support to TCP_RESET the connection to the given remote
   * peer. It works regardless of whether the recipient was initiator or
   * responder.
   */
  def abort(node: String, target: String): Future[Done]

}

trait RunControl {

  /**
   * Start the server port, returns the port number.
   */
  def startController(participants: Int): Future[Int]

  /**
   * Get the actual port used by the server.
   */
  def port: Future[Int]

  /**
   * Tell the remote node to shut itself down using System.exit with the given
   * exitValue.
   */
  def shutdown(node: String, exitValue: Int): Future[Done]

  /**
   * Tell the SBT plugin to forcibly terminate the given remote node using Process.destroy.
   */
  def kill(node: String): Future[Done]

  /**
   * Obtain the list of remote host names currently registered.
   */
  def getNodes: Future[List[String]]

  /**
   * Remove a remote host from the list, so that the remaining nodes may still
   * pass subsequent barriers.
   */
  def removeNode(node: String): Future[Done]

}
