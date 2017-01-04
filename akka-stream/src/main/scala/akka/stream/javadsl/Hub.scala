/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.javadsl

import akka.NotUsed

/**
 * A MergeHub is a special streaming hub that is able to collect streamed elements from a dynamic set of
 * producers. It consists of two parts, a [[Source]] and a [[Sink]]. The [[Source]] streams the element to a consumer from
 * its merged inputs. Once the consumer has been materialized, the [[Source]] returns a materialized value which is
 * the corresponding [[Sink]]. This [[Sink]] can then be materialized arbitrary many times, where each of the new
 * materializations will feed its consumed elements to the original [[Source]].
 */
object MergeHub {

  /**
   * Creates a [[Source]] that emits elements merged from a dynamic set of producers. After the [[Source]] returned
   * by this method is materialized, it returns a [[Sink]] as a materialized value. This [[Sink]] can be materialized
   * arbitrary many times and each of the materializations will feed the elements into the original [[Source]].
   *
   * Every new materialization of the [[Source]] results in a new, independent hub, which materializes to its own
   * [[Sink]] for feeding that materialization.
   *
   * If one of the inputs fails the [[Sink]], the [[Source]] is failed in turn (possibly jumping over already buffered
   * elements). Completed [[Sink]]s are simply removed. Once the [[Source]] is cancelled, the Hub is considered closed
   * and any new producers using the [[Sink]] will be cancelled.
   *
   * @param clazz Type of elements this hub emits and consumes
   * @param perProducerBufferSize Buffer space used per producer.
   */
  def of[T](clazz: Class[T], perProducerBufferSize: Int): Source[T, Sink[T, NotUsed]] = {
    akka.stream.scaladsl.MergeHub.source[T](perProducerBufferSize)
      .mapMaterializedValue(_.asJava)
      .asJava
  }

  /**
   * Creates a [[Source]] that emits elements merged from a dynamic set of producers. After the [[Source]] returned
   * by this method is materialized, it returns a [[Sink]] as a materialized value. This [[Sink]] can be materialized
   * arbitrary many times and each of the materializations will feed the elements into the original [[Source]].
   *
   * Every new materialization of the [[Source]] results in a new, independent hub, which materializes to its own
   * [[Sink]] for feeding that materialization.
   *
   * If one of the inputs fails the [[Sink]], the [[Source]] is failed in turn (possibly jumping over already buffered
   * elements). Completed [[Sink]]s are simply removed. Once the [[Source]] is cancelled, the Hub is considered closed
   * and any new producers using the [[Sink]] will be cancelled.
   *
   * @param clazz Type of elements this hub emits and consumes
   */
  def of[T](clazz: Class[T]): Source[T, Sink[T, NotUsed]] = of(clazz, 16)

}

/**
 * A BroadcastHub is a special streaming hub that is able to broadcast streamed elements to a dynamic set of consumers.
 * It consissts of two parts, a [[Sink]] and a [[Source]]. The [[Sink]] broadcasts elements from a producer to the
 * actually live consumers it has. Once the producer has been materialized, the [[Sink]] it feeds into returns a
 * materialized value which is the corresponding [[Source]]. This [[Source]] can be materialized arbitrary many times,
 * where weach of the new materializations will receive their elements from the original [[Sink]].
 */
object BroadcastHub {

  /**
   * Creates a [[Sink]] that receives elements from its upstream producer and broadcasts them to a dynamic set
   * of consumers. After the [[Sink]] returned by this method is materialized, it returns a [[Source]] as materialized
   * value. This [[Source]] can be materialized arbitrary many times and each materialization will receive the
   * broadcast elements form the ofiginal [[Sink]].
   *
   * Every new materialization of the [[Sink]] results in a new, independent hub, which materializes to its own
   * [[Source]] for consuming the [[Sink]] of that materialization.
   *
   * If the original [[Sink]] is failed, then the failure is immediately propagated to all of its materialized
   * [[Source]]s (possibly jumping over already buffered elements). If the original [[Sink]] is completed, then
   * all corresponding [[Source]]s are completed. Both failure and normal completion is "remembered" and later
   * materializations of the [[Source]] will see the same (failure or completion) state. [[Source]]s that are
   * cancelled are simply removed from the dynamic set of consumers.
   *
   * @param clazz Type of elements this hub emits and consumes
   * @param bufferSize Buffer size used by the producer. Gives an upper bound on how "far" from each other two
   *                   concurrent consumers can be in terms of element. If the buffer is full, the producer
   *                   is backpressured. Must be a power of two and less than 4096.
   */
  def of[T](clazz: Class[T], bufferSize: Int): Sink[T, Source[T, NotUsed]] = {
    akka.stream.scaladsl.BroadcastHub.sink[T](bufferSize)
      .mapMaterializedValue(_.asJava)
      .asJava
  }

  def of[T](clazz: Class[T]): Sink[T, Source[T, NotUsed]] = of(clazz, 256)

}
