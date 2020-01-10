/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.sourceorflow

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.scaladsl.Source
import akka.util.ByteString

import scala.concurrent.Future

object LimitWeighted {

  implicit val system: ActorSystem[_] = ???

  def simple(): Unit = {
    // #simple
    val untrustedSource: Source[ByteString, NotUsed] = Source.repeat(ByteString("element"))

    val allBytes: Future[ByteString] =
      untrustedSource.limitWeighted(max = 10000)(_.length).runReduce(_ ++ _)
    // #simple
  }

}
