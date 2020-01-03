/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.streamref

import akka.actor.ExtendedActorSystem
import akka.annotation.InternalApi
import akka.stream.SinkRef
import akka.stream.SourceRef
import akka.stream.StreamRefResolver

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class StreamRefResolverImpl(system: ExtendedActorSystem) extends StreamRefResolver {

  def toSerializationFormat[T](ref: SourceRef[T]): String = ref match {
    case SourceRefImpl(actorRef) =>
      actorRef.path.toSerializationFormatWithAddress(system.provider.getDefaultAddress)
  }

  def toSerializationFormat[T](ref: SinkRef[T]): String = ref match {
    case SinkRefImpl(actorRef) =>
      actorRef.path.toSerializationFormatWithAddress(system.provider.getDefaultAddress)
  }

  def resolveSourceRef[T](serializedSourceRef: String): SourceRef[T] =
    SourceRefImpl(system.provider.resolveActorRef(serializedSourceRef))

  def resolveSinkRef[T](serializedSinkRef: String): SinkRef[T] =
    SinkRefImpl(system.provider.resolveActorRef(serializedSinkRef))
}
