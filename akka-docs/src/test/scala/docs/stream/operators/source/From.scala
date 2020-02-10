/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.source

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source

object From {

  implicit val system: ActorSystem = null

  def fromIteratorSample(): Unit = {
    //#from-iterator
    Source.fromIterator(() => (1 to 3).iterator).runForeach(println)
    // could print
    // 1
    // 2
    // 3
    //#from-iterator
  }

}
