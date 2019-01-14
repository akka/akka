/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.NotUsed
import akka.annotation.{ DoNotInherit, InternalApi }
import akka.stream.{ Graph, SinkShape }
import akka.util.OptionVal

import scala.reflect.ClassTag
import scala.util.{ Failure, Success, Try }
import scala.collection.immutable

object PartitionOnType {

  /**
   * @return An immutable factory to build a sink that sends the elements to specific sinks based on the message type.
   */
  def apply[T](): PartitionOnType[T] = new PartitionOnTypeImpl(Nil)

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
trait PartitionOnType[T] {

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
  def addSink[B <: T](sink: Sink[B, Any])(implicit ct: ClassTag[B]): PartitionOnTypeImpl[T]

  /**
   * Create a sink out of the current set. If an element with a subtype of `T` not covered by the added sink the stream
   * reaches the sink it will fail.
   * The materialized value is a sequence with the respective materialized value of the sinks, in the order they were added.
   */
  def build(): Sink[T, immutable.Seq[Any]]
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class PartitionOnTypeImpl[T](val typesAndSinks: List[(Class[Any], Sink[Any, Any])]) extends PartitionOnType[T] {
  // FIXME support eager cancelation?

  def addSink[B <: T](sink: Sink[B, Any])(implicit ct: ClassTag[B]): PartitionOnTypeImpl[T] = {
    val clazz = ct.runtimeClass.asInstanceOf[Class[Any]]
    new PartitionOnTypeImpl[T]((clazz, sink.asInstanceOf[Sink[Any, Any]]) :: typesAndSinks)
  }

  def build(): Sink[T, immutable.Seq[Any]] =
    typesAndSinks.size match {
      case 0 ⇒ throw new IllegalStateException("Trying to build a partition on type operator with no sinks")
      case 2 ⇒ twoTypePartitioner()
      case _ ⇒ arbitraryWidthPartitioner()
    }

  // optimized for the common case of two subtypes (try, either, etc.) that can be decided
  // by an if-else instead of a traversal/lookup
  private def twoTypePartitioner(): Sink[T, immutable.Seq[Any]] = {
    // built in reverse order, so this is backwards and then mat val is again reversed
    val (type1, sink1) = typesAndSinks(1)
    val (type2, sink2) = typesAndSinks(0)

    Sink.fromGraph(GraphDSL.create(sink1, sink2)((m1, m2) ⇒ m1 :: m2 :: Nil) { implicit builder ⇒ (sink1, sink2) ⇒
      import GraphDSL.Implicits._

      val partition = builder.add(Partition[T](2, { elem ⇒
        if (type1.isInstance(elem)) 0
        else if (type2.isInstance(elem)) 1
        else throw unknownElementFailure(elem.getClass)
      }))

      partition.out(0) ~> sink1
      partition.out(1) ~> sink2

      SinkShape(partition.in)
    })

  }

  private def arbitraryWidthPartitioner(): Sink[T, immutable.Seq[Any]] = {
    // built in reverse order
    val partitions = typesAndSinks.reverse
    val typeIndexes: Array[Class[_]] = partitions.map(_._1).toArray
    val sinks = partitions.map(_._2.asInstanceOf[Graph[SinkShape[Any], Any]])

    // avoids traversal more than once for a given type
    // FIXME can we avoid boxing of the int?
    val typeCache = new java.util.HashMap[Class[_], java.lang.Integer]()

    def findPartitionFor(elem: T): Int = {
      var idx = 0
      var partition = -1
      while (idx < partitions.size && partition == -1) {
        val typeAtIndex = typeIndexes(idx)
        if (typeAtIndex.isInstance(elem))
          partition = idx
        idx += 1
      }
      if (partition > -1) partition
      else throw unknownElementFailure(elem.getClass)
    }

    Sink.fromGraph(GraphDSL.create(sinks) { implicit builder ⇒ (sinks: immutable.Seq[SinkShape[Any]]) ⇒
      import GraphDSL.Implicits._

      val partition = builder.add(Partition[T](
        partitions.size,
        { element ⇒
          val elementType = element.getClass
          OptionVal(typeCache.get(elementType)) match {
            case OptionVal.Some(n) ⇒ n
            case OptionVal.None ⇒
              val partition = findPartitionFor(element)
              typeCache.put(elementType, partition)
              partition
          }
        }
      ))

      sinks.zipWithIndex.foreach {
        case (sink, idx) ⇒
          // we know the types are safe because the addSink signature
          partition.out(idx).as[AnyRef] ~> sink
      }

      SinkShape(partition.in)
    }).mapMaterializedValue(_.reverse) // mat vals in the order the sinks were added
  }

  private def unknownElementFailure(elementType: Class[_]) =
    new RuntimeException(s"Element of type [${elementType.getName}] not covered by this PartitionOnType")
}
