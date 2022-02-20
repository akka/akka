/*
 * Copyright (C) 2019-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sbr

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.actor.Props
import akka.cluster.Cluster
import akka.pattern.pipe
import akka.remote.RemoteActorRefProvider
import akka.remote.transport.ThrottlerTransportAdapter.Blackhole
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.remote.transport.ThrottlerTransportAdapter.SetThrottle
import akka.remote.transport.ThrottlerTransportAdapter.Unthrottled
import akka.serialization.jackson.CborSerializable

object GremlinController {
  final case class BlackholeNode(target: Address) extends CborSerializable
  final case class PassThroughNode(target: Address) extends CborSerializable
  case object GetAddress extends CborSerializable

  def props: Props =
    Props(new GremlinController)
}

class GremlinController extends Actor with ActorLogging {
  import context.dispatcher

  import GremlinController._
  val transport =
    context.system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].transport
  val selfAddress = Cluster(context.system).selfAddress

  override def receive = {
    case GetAddress =>
      sender() ! selfAddress
    case BlackholeNode(target) =>
      log.debug("Blackhole {} <-> {}", selfAddress, target)
      transport.managementCommand(SetThrottle(target, Direction.Both, Blackhole)).pipeTo(sender())
    case PassThroughNode(target) =>
      log.debug("PassThrough {} <-> {}", selfAddress, target)
      transport.managementCommand(SetThrottle(target, Direction.Both, Unthrottled)).pipeTo(sender())
  }
}

object GremlinControllerProxy {
  def props(target: ActorRef): Props =
    Props(new GremlinControllerProxy(target))
}

class GremlinControllerProxy(target: ActorRef) extends Actor {
  override def receive = {
    case msg => target.forward(msg)
  }
}
