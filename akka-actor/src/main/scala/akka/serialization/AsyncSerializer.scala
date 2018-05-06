/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization

import akka.actor.ExtendedActorSystem

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }

/**
 * Serializer that supports async serialization.
 *
 * Only used for Akka persistence journals that explicitly support async serializers.
 *
 * Implementations should typically extend [[AsyncSerializerWithStringManifest]] that
 * delegates synchronous calls to their async equivalents.
 */
trait AsyncSerializer {
  def toBinaryAsync(o: AnyRef): Future[Array[Byte]]

  def fromBinaryAsync(bytes: Array[Byte], manifest: String): Future[AnyRef]
}

/**
 * Async serializer with string manifest that delegates synchronous calls to the asynchronous calls
 * and blocks.
 */
abstract class AsyncSerializerWithStringManifest(system: ExtendedActorSystem) extends SerializerWithStringManifest with AsyncSerializer {
  final override def toBinary(o: AnyRef): Array[Byte] = {
    system.log.warning("Async serializer called synchronously. This will block. Async serializers should only be used for akka persistence plugins that support them. Class: {}", o.getClass)
    Await.result(toBinaryAsync(o), Duration.Inf)
  }

  final override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    system.log.warning("Async serializer called synchronously. This will block. Async serializers should only be used for akka persistence plugins that support them. Manifest: [{}]", manifest)
    Await.result(fromBinaryAsync(bytes, manifest), Duration.Inf)
  }
}

