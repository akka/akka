/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.annotation.InternalApi
import akka.stream.{ Graph, SinkShape }
import akka.stream.scaladsl.{ GraphDSL, Partition, PartitionByType, Sink }
import akka.util.OptionVal

import scala.collection.immutable
import scala.reflect.ClassTag

@InternalApi
private[akka] final class PartitionByTypeImpl[T](val typesAndSinks: List[(Class[Any], Sink[Any, Any])]) extends PartitionByType[T] {

  def this() = this(Nil)

  // FIXME support eager cancelation?

  def addSink[B <: T](sink: Sink[B, Any])(implicit ct: ClassTag[B]): PartitionByTypeImpl[T] = {
    val clazz = ct.runtimeClass.asInstanceOf[Class[Any]]
    new PartitionByTypeImpl[T]((clazz, sink.asInstanceOf[Sink[Any, Any]]) :: typesAndSinks)
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
