/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream

import org.reactivestreams.Publisher

trait CancellablePublisher[T] extends Publisher[T] {
  def cancel()
}
