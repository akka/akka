/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl

import akka.stream.impl.PartitionByTypeImpl

import scala.reflect.ClassTag
import scala.collection.JavaConverters._

object PartitionByType {

  /**
   * @return A mutable factory to build a sink that sends the elements to specific sinks based on the message type.
   */
  def create[T](): PartitionByType[T] = new PartitionByType[T](new PartitionByTypeImpl(Nil))

  /**
   * @return An immutable factory to build a sink that sends the elements to specific sinks based on the message type.
   */
  def create[T](streamType: Class[T]): PartitionByType[T] = create[T]()

}

/**
 * A mutable builder to create a stream stage that diverts stream elements to different sinks depending on the element type.
 *
 * Note that special care must be taken to cover all subtypes of `T` since there can be no type system check for completeness.
 *
 * @tparam T a common supertype for the elements that can be partitioned on
 */
final class PartitionByType[T] private (private var delegate: PartitionByTypeImpl[T]) {
  /**
   * Add a sink that will receive elements for the type `B` (including subtypes of `B`). The last added sink that
   * matches the type of a message will be used, but adding several sinks with overlapping types may have performance
   * consequences.
   *
   * Note that partitioning Scala primitives (`Int`, `Long` etc) requires a deeper understanding of Scala-Java
   * interoperability and may yield surprising results lacking that.
   *
   * @param sink A sink that will receive elements of type `B`
   */
  def addSink[B <: T, Mat](elementClass: Class[B], sink: Sink[B, Mat]): PartitionByType[T] = {
    delegate = delegate.addSink(sink.asScala)(ClassTag(elementClass))
    this
  }

  /**
   * Create a sink out of the current set. If an element with a subtype of `T` not covered by the added sink the stream
   * reaches the sink it will fail.
   * The materialized value is a sequence with the respective materialized value of the sinks, in the order they were added.
   */
  def build(): Sink[T, java.util.List[Any]] =
    delegate.build().mapMaterializedValue(_.asJava).asJava

}
