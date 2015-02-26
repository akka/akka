/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream.javadsl

object FlattenStrategy {

  /**
   * Strategy that flattens a stream of streams by concatenating them. This means taking an incoming stream
   * emitting its elements directly to the output until it completes and then taking the next stream. This has the
   * consequence that if one of the input stream is infinite, no other streams after that will be consumed from.
   */
  def concat[T]: akka.stream.FlattenStrategy[javadsl.Source[T, Unit], T] =
    akka.stream.FlattenStrategy.Concat[T]().asInstanceOf[akka.stream.FlattenStrategy[javadsl.Source[T, _], T]]
  // TODO so in theory this should be safe, but let's rethink the design later

}
