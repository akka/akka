/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import akka.util.Duration
import akka.util.duration._
import akka.zeromq.SocketType._
import org.zeromq.{ ZMQ ⇒ JZMQ }
import akka.actor._
import akka.dispatch.Await

case class SocketParameters(
  socketType: SocketType,
  context: Context,
  listener: Option[ActorRef] = None,
  deserializer: Deserializer = new ZMQMessageDeserializer,
  pollTimeoutDuration: Duration = 100 millis)

case class ZeroMQVersion(major: Int, minor: Int, patch: Int) {
  override def toString = "%d.%d.%d".format(major, minor, patch)
}

object ZeroMQExtension extends ExtensionId[ZeroMQExtension] with ExtensionIdProvider {
  def lookup() = this
  def createExtension(system: ActorSystemImpl) = new ZeroMQExtension(system)
}
class ZeroMQExtension(system: ActorSystem) extends Extension {

  def version = {
    ZeroMQVersion(JZMQ.getMajorVersion, JZMQ.getMinorVersion, JZMQ.getPatchVersion)
  }

  lazy val DefaultContext = newContext()

  def newContext(numIoThreads: Int = 1) = {
    verifyZeroMQVersion
    new Context(numIoThreads)
  }

  def newSocket(socketType: SocketType,
                listener: Option[ActorRef] = None,
                context: Context = DefaultContext, // For most applications you want to use the default context
                deserializer: Deserializer = new ZMQMessageDeserializer,
                pollTimeoutDuration: Duration = 100 millis) = {
    verifyZeroMQVersion
    val params = SocketParameters(socketType, context, listener, deserializer, pollTimeoutDuration)
    implicit val timeout = system.settings.ActorTimeout
    val req = (zeromq ? Props(new ConcurrentSocketActor(params)).withDispatcher("zmqdispatcher")).mapTo[ActorRef]
    Await.result(req, timeout.duration)
  }

  val zeromq: ActorRef = {
    verifyZeroMQVersion
    system.asInstanceOf[ActorSystemImpl].systemActorOf(Props(new Actor {
      protected def receive = { case p: Props ⇒ sender ! context.actorOf(p) }
    }), "zeromq")
  }

  private def verifyZeroMQVersion = {
    require(
      JZMQ.getFullVersion > JZMQ.makeVersion(2, 1, 0),
      "Unsupported ZeroMQ version: %s".format(JZMQ.getVersionString))
  }
}
