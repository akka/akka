/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization

import java.util.concurrent.CompletionStage

import akka.actor.ExtendedActorSystem
import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }

/**
 * Serializer that supports async serialization.
 *
 * Only used for Akka persistence journals that explicitly support async serializers.
 *
 * Implementations should typically extend [[AsyncSerializerWithStringManifest]] or
 * [[AsyncSerializerWithStringManifestCS]] that delegates synchronous calls to their async equivalents.
 */
trait AsyncSerializer {

  /**
   * Serializes the given object into an Array of Byte
   */
  def toBinaryAsync(o: AnyRef): Future[Array[Byte]]

  /**
   * Produces an object from an array of bytes, with an optional type-hint.
   */
  def fromBinaryAsync(bytes: Array[Byte], manifest: String): Future[AnyRef]
}

/**
 * Scala API: Async serializer with string manifest that delegates synchronous calls to the asynchronous calls
 * and blocks.
 */
abstract class AsyncSerializerWithStringManifest(system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with AsyncSerializer {
  final override def toBinary(o: AnyRef): Array[Byte] = {
    system.log.warning(
      "Async serializer called synchronously. This will block. Async serializers should only be used for akka persistence plugins that support them. Class: {}",
      o.getClass)
    Await.result(toBinaryAsync(o), Duration.Inf)
  }

  final override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    system.log.warning(
      "Async serializer called synchronously. This will block. Async serializers should only be used for akka persistence plugins that support them. Manifest: [{}]",
      manifest)
    Await.result(fromBinaryAsync(bytes, manifest), Duration.Inf)
  }
}

/**
 * Java API: Async serializer with string manifest that delegates synchronous calls to the asynchronous calls
 * and blocks.
 */
abstract class AsyncSerializerWithStringManifestCS(system: ExtendedActorSystem)
    extends AsyncSerializerWithStringManifest(system) {
  import scala.compat.java8.FutureConverters._

  def toBinaryAsyncCS(o: AnyRef): CompletionStage[Array[Byte]]

  def fromBinaryAsyncCS(bytes: Array[Byte], manifest: String): CompletionStage[AnyRef]

  /**
   * Delegates to [[AsyncSerializerWithStringManifestCS#toBinaryAsyncCS]]
   */
  final def toBinaryAsync(o: AnyRef): Future[Array[Byte]] =
    toBinaryAsyncCS(o).toScala

  /**
   * Delegates to [[AsyncSerializerWithStringManifestCS#fromBinaryAsyncCS]]
   */
  def fromBinaryAsync(bytes: Array[Byte], manifest: String): Future[AnyRef] =
    fromBinaryAsyncCS(bytes, manifest).toScala
}
