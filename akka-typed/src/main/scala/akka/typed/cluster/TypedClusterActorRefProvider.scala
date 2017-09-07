/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster

import akka.typed.scaladsl.adapter._
import akka.cluster.ClusterActorRefProvider
import akka.actor.DynamicAccess
import akka.actor.ActorSystem
import akka.event.EventStream
import akka.typed.ActorRef
import akka.remote.RemoteActorRef

class TypedClusterActorRefProvider(
  _systemName:    String,
  _settings:      ActorSystem.Settings,
  _eventStream:   EventStream,
  _dynamicAccess: DynamicAccess)
  extends ClusterActorRefProvider(_systemName, _settings, _eventStream, _dynamicAccess) {

  def resolveTypedActorRef[T](path: String): ActorRef[T] = {
    super.resolveActorRef(path) match {
      case r: RemoteActorRef ⇒ new TypedRemoteActorRef[T](r, transport)
      case other             ⇒ other
    }
  }
}
