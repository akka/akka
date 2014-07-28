/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.Props
import akka.stream.MaterializerSettings
import akka.stream.Stop

/**
 * INTERNAL API
 */
private[akka] object IteratorPublisher {
  def props(iterator: Iterator[Any], settings: MaterializerSettings): Props = {
    def f(): Any = {
      if (!iterator.hasNext) throw Stop
      iterator.next()
    }
    SimpleCallbackPublisher.props(settings, f)
  }

}