/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer

import scala.io.StdIn

object SubStreamMemoryLeaks extends App {

  implicit val system = ActorSystem()
  implicit val mat = ActorMaterializer()

  StdIn.readLine()

  Source.fromIterator(() ⇒
    Iterator.continually(Source.single(Array.fill(1024 * 1024)(1)))
  ) // each incoming of these will cause creation of a new async callback
    // which would leak because of bug 24046
    .flatMapConcat(identity)
    .grouped(100)
    .runWith(Sink.foreach(_ ⇒ print(".")))

}
