/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream

import scala.collection.immutable

private[akka] abstract class TransformerLike[-T, +U] {
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
   * This method is only called if [[#onError]] does not throw an exception. The default implementation
   * of [[#onError]] throws the received cause forcing the failure to propagate downstream immediately.
   *
   * @param e Contains a non-empty option with the error causing the termination or an empty option
   *          if the Transformer was completed normally
   */
  def onTermination(e: Option[Throwable]): immutable.Seq[U] = Nil

  /**
   * Invoked when failure is signaled from upstream. If this method throws an exception, then onError is immediately
   * propagated downstream. If this method completes normally then [[#onTermination]] is invoked as a final
   * step, passing the original cause.
   */
  def onError(cause: Throwable): Unit = throw cause

  /**
   * Invoked after normal completion or failure.
   */
  def cleanup(): Unit = ()

}

