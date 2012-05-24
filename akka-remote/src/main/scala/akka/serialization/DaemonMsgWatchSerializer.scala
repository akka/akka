/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

import akka.actor.ActorRef
import akka.remote.DaemonMsgWatch
import akka.remote.RemoteProtocol.ActorRefProtocol
import akka.remote.RemoteProtocol.DaemonMsgWatchProtocol
import akka.actor.ExtendedActorSystem

/**
 * Serializes akka's internal DaemonMsgWatch using protobuf.
 *
 * INTERNAL API
 */
private[akka] class DaemonMsgWatchSerializer(val system: ExtendedActorSystem) extends Serializer {
  import ProtobufSerializer.serializeActorRef
  import ProtobufSerializer.deserializeActorRef

  def includeManifest: Boolean = false
  def identifier = 4

  def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case DaemonMsgWatch(watcher, watched) ⇒
      DaemonMsgWatchProtocol.newBuilder.
        setWatcher(serializeActorRef(watcher)).
        setWatched(serializeActorRef(watched)).
        build.toByteArray
    case _ ⇒
      throw new IllegalArgumentException(
        "Can't serialize a non-DaemonMsgWatch message using DaemonMsgWatchSerializer [%s]".format(obj))
  }

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = {
    val proto = DaemonMsgWatchProtocol.parseFrom(bytes)
    DaemonMsgWatch(
      watcher = deserializeActorRef(system, proto.getWatcher),
      watched = deserializeActorRef(system, proto.getWatched))
  }

}