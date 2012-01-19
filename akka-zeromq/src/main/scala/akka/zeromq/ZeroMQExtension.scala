/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import akka.util.duration._
import org.zeromq.{ ZMQ ⇒ JZMQ }
import akka.actor._
import akka.dispatch.{ Await }

/**
 * A Model to represent a version of the zeromq library
 * @param major
 * @param minor
 * @param patch
 */
case class ZeroMQVersion(major: Int, minor: Int, patch: Int) {
  override def toString = "%d.%d.%d".format(major, minor, patch)
}

/**
 * The [[akka.actor.ExtensionId]] and [[akka.actor.ExtensionIdProvider]] for the ZeroMQ module
 */
object ZeroMQExtension extends ExtensionId[ZeroMQExtension] with ExtensionIdProvider {
  def lookup() = this
  def createExtension(system: ActorSystemImpl) = new ZeroMQExtension(system)

  private val minVersionString = "2.1.0"
  private val minVersion = JZMQ.makeVersion(2, 1, 0)

  private[zeromq] def check[TOption <: SocketOption: Manifest](parameters: Seq[SocketOption]) = {
    parameters exists { p ⇒
      ClassManifest.singleType(p) <:< manifest[TOption]
    }
  }
}

/**
 * The extension for the ZeroMQ module
 *
 * @param system The ActorSystem this extension belongs to.
 */
class ZeroMQExtension(system: ActorSystem) extends Extension {

  /**
   * The version of the ZeroMQ library
   * @return a [[akka.zeromq.ZeroMQVersion]]
   */
  def version = {
    ZeroMQVersion(JZMQ.getMajorVersion, JZMQ.getMinorVersion, JZMQ.getPatchVersion)
  }

  /**
   * Factory method to create the [[akka.actor.Props]] to build the ZeroMQ socket actor.
   *
   * @param socketParameters a varargs list of [[akka.zeromq.SocketOption]] to configure the socket
   * @return the [[akka.actor.Props]]
   */
  def newSocketProps(socketParameters: SocketOption*): Props = {
    verifyZeroMQVersion
    require(ZeroMQExtension.check[SocketType.ZMQSocketType](socketParameters), "A socket type is required")
    Props(new ConcurrentSocketActor(socketParameters)).withDispatcher("akka.zeromq.socket-dispatcher")
  }

  /**
   * Factory method to create the actor representing the ZeroMQ socket.
   * You can pass in as many configuration options as you want and the order of the configuration options doesn't matter
   * They are matched on type and the first one found wins.
   *
   * @param socketParameters a varargs list of [[akka.zeromq.SocketOption]] to configure the socke
   * @return
   */
  def newSocket(socketParameters: SocketOption*): ActorRef = {
    implicit val timeout = system.settings.ActorTimeout
    val req = (zeromqGuardian ? newSocketProps(socketParameters: _*)).mapTo[ActorRef]
    Await.result(req, timeout.duration)
  }

  private val zeromqGuardian: ActorRef = {
    verifyZeroMQVersion
    system.actorOf(Props(new Actor {
      def receive = { case p: Props ⇒ sender ! context.actorOf(p) }
    }), "zeromq")
  }

  private def verifyZeroMQVersion = {
    require(
      JZMQ.getFullVersion > ZeroMQExtension.minVersion,
      "Unsupported ZeroMQ version: %s, akka needs at least: %s".format(JZMQ.getVersionString, ZeroMQExtension.minVersionString))
  }
}
