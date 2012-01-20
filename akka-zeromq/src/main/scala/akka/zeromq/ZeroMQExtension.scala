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
   * Java API helper
   * Factory method to create the [[akka.actor.Props]] to build a ZeroMQ Publisher socket actor.
   * @param socketParameters a varargs list of [[akka.zeromq.SocketOption]] to configure the socket
   * @return the [[akka.actor.Props]]
   */
  def newPubSocketProps(socketParameters: SocketOption*): Props = newSocketProps((SocketType.Pub +: socketParameters): _*)

  /**
   * Java API helper
   * Factory method to create the [[akka.actor.Props]] to build a ZeroMQ Subscriber socket actor.
   * @param socketParameters a varargs list of [[akka.zeromq.SocketOption]] to configure the socket
   * @return the [[akka.actor.Props]]
   */
  def newSubSocketProps(socketParameters: SocketOption*): Props = newSocketProps((SocketType.Sub +: socketParameters): _*)

  /**
   * Java API helper
   * Factory method to create the [[akka.actor.Props]] to build a ZeroMQ Dealer socket actor.
   * @param socketParameters a varargs list of [[akka.zeromq.SocketOption]] to configure the socket
   * @return the [[akka.actor.Props]]
   */
  def newDealerSocketProps(socketParameters: SocketOption*): Props = newSocketProps((SocketType.Dealer +: socketParameters): _*)

  /**
   * Java API helper
   * Factory method to create the [[akka.actor.Props]] to build a ZeroMQ Router socket actor.
   * @param socketParameters a varargs list of [[akka.zeromq.SocketOption]] to configure the socket
   * @return the [[akka.actor.Props]]
   */
  def newRouterSocketProps(socketParameters: SocketOption*): Props = newSocketProps((SocketType.Router +: socketParameters): _*)

  /**
   * Java API helper
   * Factory method to create the [[akka.actor.Props]] to build a ZeroMQ Push socket actor.
   * @param socketParameters a varargs list of [[akka.zeromq.SocketOption]] to configure the socket
   * @return the [[akka.actor.Props]]
   */
  def newPushSocketProps(socketParameters: SocketOption*): Props = newSocketProps((SocketType.Push +: socketParameters): _*)

  /**
   * Java API helper
   * Factory method to create the [[akka.actor.Props]] to build a ZeroMQ Pull socket actor.
   * @param socketParameters a varargs list of [[akka.zeromq.SocketOption]] to configure the socket
   * @return the [[akka.actor.Props]]
   */
  def newPullSocketProps(socketParameters: SocketOption*): Props = newSocketProps((SocketType.Pull +: socketParameters): _*)

  /**
   * Java API helper
   * Factory method to create the [[akka.actor.Props]] to build a ZeroMQ Req socket actor.
   * @param socketParameters a varargs list of [[akka.zeromq.SocketOption]] to configure the socket
   * @return the [[akka.actor.Props]]
   */
  def newReqSocketProps(socketParameters: SocketOption*): Props = newSocketProps((SocketType.Rep +: socketParameters): _*)

  /**
   * Java API helper
   * Factory method to create the [[akka.actor.Props]] to build a ZeroMQ Rep socket actor.
   * @param socketParameters a varargs list of [[akka.zeromq.SocketOption]] to configure the socket
   * @return the [[akka.actor.Props]]
   */
  def newRepSocketProps(socketParameters: SocketOption*): Props = newSocketProps((SocketType.Req +: socketParameters): _*)

  /**
   * Factory method to create the actor representing the ZeroMQ socket.
   * You can pass in as many configuration options as you want and the order of the configuration options doesn't matter
   * They are matched on type and the first one found wins.
   *
   * @param socketParameters a varargs list of [[akka.zeromq.SocketOption]] to configure the socke
   * @return the [[akka.actor.ActorRef]]
   */
  def newSocket(socketParameters: SocketOption*): ActorRef = {
    implicit val timeout = system.settings.ActorTimeout
    val req = (zeromqGuardian ? newSocketProps(socketParameters: _*)).mapTo[ActorRef]
    Await.result(req, timeout.duration)
  }

  /**
   * Java API helper
   * Factory method to create the actor representing the ZeroMQ Publisher socket.
   * You can pass in as many configuration options as you want and the order of the configuration options doesn't matter
   * They are matched on type and the first one found wins.
   *
   * @param socketParameters a varargs list of [[akka.zeromq.SocketOption]] to configure the socke
   * @return the [[akka.actor.ActorRef]]
   */
  def newPubSocket(socketParameters: SocketOption*): ActorRef = newSocket((SocketType.Pub +: socketParameters): _*)

  /**
   * Java API helper
   * Factory method to create the actor representing the ZeroMQ Subscriber socket.
   * You can pass in as many configuration options as you want and the order of the configuration options doesn't matter
   * They are matched on type and the first one found wins.
   *
   * @param socketParameters a varargs list of [[akka.zeromq.SocketOption]] to configure the socke
   * @return the [[akka.actor.ActorRef]]
   */
  def newSubSocket(socketParameters: SocketOption*): ActorRef = newSocket((SocketType.Sub +: socketParameters): _*)

  /**
   * Java API helper
   * Factory method to create the actor representing the ZeroMQ Dealer socket.
   * You can pass in as many configuration options as you want and the order of the configuration options doesn't matter
   * They are matched on type and the first one found wins.
   *
   * @param socketParameters a varargs list of [[akka.zeromq.SocketOption]] to configure the socke
   * @return the [[akka.actor.ActorRef]]
   */
  def newDealerSocket(socketParameters: SocketOption*): ActorRef = newSocket((SocketType.Dealer +: socketParameters): _*)

  /**
   * Java API helper
   * Factory method to create the actor representing the ZeroMQ Router socket.
   * You can pass in as many configuration options as you want and the order of the configuration options doesn't matter
   * They are matched on type and the first one found wins.
   *
   * @param socketParameters a varargs list of [[akka.zeromq.SocketOption]] to configure the socke
   * @return the [[akka.actor.ActorRef]]
   */
  def newRouterSocket(socketParameters: SocketOption*): ActorRef = newSocket((SocketType.Router +: socketParameters): _*)

  /**
   * Java API helper
   * Factory method to create the actor representing the ZeroMQ Push socket.
   * You can pass in as many configuration options as you want and the order of the configuration options doesn't matter
   * They are matched on type and the first one found wins.
   *
   * @param socketParameters a varargs list of [[akka.zeromq.SocketOption]] to configure the socke
   * @return the [[akka.actor.ActorRef]]
   */
  def newPushSocket(socketParameters: SocketOption*): ActorRef = newSocket((SocketType.Push +: socketParameters): _*)

  /**
   * Java API helper
   * Factory method to create the actor representing the ZeroMQ Pull socket.
   * You can pass in as many configuration options as you want and the order of the configuration options doesn't matter
   * They are matched on type and the first one found wins.
   *
   * @param socketParameters a varargs list of [[akka.zeromq.SocketOption]] to configure the socke
   * @return the [[akka.actor.ActorRef]]
   */
  def newPullSocket(socketParameters: SocketOption*): ActorRef = newSocket((SocketType.Pull +: socketParameters): _*)

  /**
   * Java API helper
   * Factory method to create the actor representing the ZeroMQ Req socket.
   * You can pass in as many configuration options as you want and the order of the configuration options doesn't matter
   * They are matched on type and the first one found wins.
   *
   * @param socketParameters a varargs list of [[akka.zeromq.SocketOption]] to configure the socke
   * @return the [[akka.actor.ActorRef]]
   */
  def newReqSocket(socketParameters: SocketOption*): ActorRef = newSocket((SocketType.Req +: socketParameters): _*)

  /**
   * Java API helper
   * Factory method to create the actor representing the ZeroMQ Rep socket.
   * You can pass in as many configuration options as you want and the order of the configuration options doesn't matter
   * They are matched on type and the first one found wins.
   *
   * @param socketParameters a varargs list of [[akka.zeromq.SocketOption]] to configure the socke
   * @return the [[akka.actor.ActorRef]]
   */
  def newRepSocket(socketParameters: SocketOption*): ActorRef = newSocket((SocketType.Rep +: socketParameters): _*)

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
