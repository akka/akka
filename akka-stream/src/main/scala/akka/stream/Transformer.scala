/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.collection.immutable

/**
 * General interface for stream transformation.
 *
 * It is possible to keep state in the concrete [[Transformer]] instance with
 * ordinary instance variables. The [[Transformer]] is executed by an actor and
 * therefore you don not have to add any additional thread safety or memory
 * visibility constructs to access the state from the callback methods.
 *
 * @see [[akka.stream.scaladsl.Flow#transform]]
 * @see [[akka.stream.javadsl.Flow#transform]]
 */
abstract class Transformer[-T, +U] {
  /**
   * Invoked for each element to produce a (possibly empty) sequence of
   * output elements.
   */
  def onNext(element: T): immutable.Seq[U]

  /**
   * Invoked after handing off the elements produced from one input element to the
   * downstream consumers to determine whether to end stream processing at this point;
   * in that case the upstream subscription is canceled.
   */
  def isComplete: Boolean = false

  /**
   * Invoked before signaling normal completion to the downstream consumers
   * to produce a (possibly empty) sequence of elements in response to the
   * end-of-stream event.
   */
  def onComplete(): immutable.Seq[U] = Nil

  /**
   * Invoked when failure is signaled from upstream.
   */
  def onError(cause: Throwable): Unit = ()

  /**
   * Invoked after normal completion or error.
   */
  def cleanup(): Unit = ()

  /**
   * Name of this transformation step. Used as part of the actor name.
   * Facilitates debugging and logging.
   */
  def name: String = "transform"
}

/**
 * General interface for stream transformation.
 * @see [[akka.stream.scaladsl.Flow#transformRecover]]
 * @see [[akka.stream.javadsl.Flow#transformRecover]]
 * @see [[Transformer]]
 */
abstract class RecoveryTransformer[-T, +U] extends Transformer[T, U] {
  /**
   * Invoked when failure is signaled from upstream to emit an additional
   * sequence of elements before the stream ends.
   */
  def onErrorRecover(cause: Throwable): immutable.Seq[U]

  /**
   * Name of this transformation step. Used as part of the actor name.
   * Facilitates debugging and logging.
   */
  override def name: String = "transformRecover"
}