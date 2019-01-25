/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.NotUsed
import akka.annotation.DoNotInherit
import akka.stream.impl.PartitionByTypeImpl

import scala.collection.immutable
import scala.reflect.ClassTag
import scala.util.{ Failure, Success, Try }

object PartitionByType {

  /**
   * @tparam T a common supertype for the elements that can be partitioned on
   * @return An immutable factory to build a sink that sends the elements to specific sinks based on the message type.
   */
  def apply[T](): PartitionByType[T] = new PartitionByTypeImpl(Nil)

  /**
   * A prebuilt partition on `scala.util.Try` that will divert successful values to one sink and
   * failures down a separate sink
   */
  def forTry[T, Mat1, Mat2](successful: Sink[T, Mat1], failures: Sink[Throwable, Mat2]): Sink[Try[T], NotUsed] =
    forTryMat(successful, failures)(Keep.none)

  /**
   * A prebuilt partition on `scala.util.Try` that will divert successful values to one sink and
   * failures down a separate sink.
   *
   * The `combine` function is used to compose the materialized values of the two sinks.
   */
  def forTryMat[T, Mat1, Mat2, Mat3](successful: Sink[T, Mat1], failures: Sink[Throwable, Mat2])(combine: (Mat1, Mat2) ⇒ Mat3): Sink[Try[T], Mat3] =
    apply[Try[T]]()
      .addSink(successful.contramap((s: Success[T]) ⇒ s.value))
      .addSink(failures.contramap((f: Failure[T]) ⇒ f.exception))
      .build()
      .mapMaterializedValue(matVals ⇒
        combine(matVals(0).asInstanceOf[Mat1], matVals(1).asInstanceOf[Mat2])
      )

  /**
   * A prebuilt partition on `scala.util.Either` that will divert `Left` values to one sink and
   * `Right` values down a separate sink.
   */
  def forEither[L, R](left: Sink[L, _], right: Sink[R, _]): Sink[Either[L, R], NotUsed] =
    forEitherMat(left, right)(Keep.none)

  /**
   * A prebuilt partition on `scala.util.Either` that will divert `Left` values to one sink and
   * `Right` values down a separate sink.
   *
   * The `combine` function is used to compose the materialized values of the two sinks.
   */
  def forEitherMat[L, R, Mat1, Mat2, Mat3](left: Sink[L, Mat1], right: Sink[R, Mat2])(combine: (Mat1, Mat2) ⇒ Mat3): Sink[Either[L, R], Mat3] =
    apply[Either[L, R]]()
      .addSink(left.contramap((l: Left[L, R]) ⇒ l.value))
      .addSink(right.contramap((r: Right[L, R]) ⇒ r.value))
      .build()
      .mapMaterializedValue(matVals ⇒
        combine(matVals(0).asInstanceOf[Mat1], matVals(1).asInstanceOf[Mat2])
      )
}

/**
 * An immutable builder to create a stream stage that diverts stream elements to different sinks depending on the element type.
 *
 * Note that special care must be taken to cover all subtypes of `T` since there can be no type system check for completeness.
 *
 * @tparam T a common supertype for the elements that can be partitioned on
 *
 * Not for user extension
 */
@DoNotInherit
trait PartitionByType[T] {

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
  def addSink[B <: T](sink: Sink[B, Any])(implicit ct: ClassTag[B]): PartitionByTypeImpl[T]

  /**
   * Create a sink out of the current set. If an element with a subtype of `T` not covered by the added sink the stream
   * reaches the sink it will fail.
   * The materialized value is a sequence with the respective materialized value of the sinks, in the order they were added.
   */
  def build(): Sink[T, immutable.Seq[Any]]
}
