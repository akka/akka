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
   * downstream subscribers to determine whether to end stream processing at this point;
   * in that case the upstream subscription is canceled.
   */
  def isComplete: Boolean = false

  /**
   * Invoked before the Transformer terminates (either normal completion or after an onError)
   * to produce a (possibly empty) sequence of elements in response to the
   * end-of-stream event.
   *
   * This method is only called if [[Transformer#onError]] does not throw an exception. The default implementation
   * of [[Transformer#onError]] throws the received cause forcing the error to propagate downstream immediately.
   *
   * @param e Contains a non-empty option with the error causing the termination or an empty option
   *          if the Transformer was completed normally
   */
  def onTermination(e: Option[Throwable]): immutable.Seq[U] = Nil

  /**
   * Invoked when failure is signaled from upstream. If this method throws an exception, then onError is immediately
   * propagated downstream. If this method completes normally then [[Transformer#onTermination]] is invoked as a final
   * step, passing the original cause.
   */
  def onError(cause: Throwable): Unit = throw cause

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
