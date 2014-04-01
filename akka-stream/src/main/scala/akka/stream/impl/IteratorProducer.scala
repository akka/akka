/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.actor.Props
import akka.stream.GeneratorSettings
import akka.stream.Stop

/**
 * INTERNAL API
 */
private[akka] object IteratorProducer {
  def props(iterator: Iterator[Any], settings: GeneratorSettings): Props = {
    def f(): Any = {
      if (!iterator.hasNext) throw Stop
      iterator.next()
    }
    ActorProducer.props(settings, f)
  }

}