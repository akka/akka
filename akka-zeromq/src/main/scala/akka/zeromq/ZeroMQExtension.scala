/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import akka.util.Duration
import akka.util.duration._
import org.zeromq.{ ZMQ ⇒ JZMQ }
import akka.actor._
import akka.dispatch.{ Dispatcher, Await }
import collection.mutable.ListBuffer

case class ZeroMQVersion(major: Int, minor: Int, patch: Int) {
  override def toString = "%d.%d.%d".format(major, minor, patch)
}

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
class ZeroMQExtension(system: ActorSystem) extends Extension {

  def version = {
    ZeroMQVersion(JZMQ.getMajorVersion, JZMQ.getMinorVersion, JZMQ.getPatchVersion)
  }

  def newSocketProps(socketParameters: SocketOption*): Props = {
    verifyZeroMQVersion
    require(ZeroMQExtension.check[SocketType.ZMQSocketType](socketParameters), "A socket type is required")
    Props(new ConcurrentSocketActor(socketParameters)).withDispatcher("akka.zeromq.socket-dispatcher")
  }

  def newSocket(socketParameters: SocketOption*): ActorRef = {
    implicit val timeout = system.settings.ActorTimeout
    val req = (zeromq ? newSocketProps(socketParameters: _*)).mapTo[ActorRef]
    Await.result(req, timeout.duration)
  }

  val zeromq: ActorRef = {
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
