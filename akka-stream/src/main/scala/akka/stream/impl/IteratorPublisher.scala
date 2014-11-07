/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.Props
import akka.stream.MaterializerSettings

/**
 * INTERNAL API
 */
private[akka] object IteratorPublisher {
  def props[T](iterator: Iterator[T], settings: MaterializerSettings): Props =
    Props(new IteratorPublisherImpl(iterator, settings)).withDispatcher(settings.dispatcher)

}