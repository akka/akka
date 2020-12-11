/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.sourceorflow

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Keep, Source}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object WatchTermination {

  def watchTerminationExample(): Unit = {
    implicit val system: ActorSystem = ???
    implicit val ec: ExecutionContext = ???

    //#watchTermination
    Source(List(() => 1, () => 2, () => 3))
      .watchTermination()(Keep.none) // discard the materialized value of the stream as we're not interested in it
    // we can also use Keep.right, Keep.left, or Keep.both depending on our needs wrt the materialized value
      .runForeach(e => println(e()))
      .onComplete {
        case Failure(exception) => println(exception.getMessage)
        case Success(_) => println("Done")
      }
    /*
    Prints:
    1
    2
    3
    Done
     */

    Source(List(() => 1, () => 2, () => throw new Exception("Boom"), () => 3))
      .watchTermination()(Keep.none)
      .runForeach(e => println(e()))
      .onComplete {
        case Failure(exception) => println(exception.getMessage)
        case Success(value) => println(value)
      }
    /*
    Prints:
    1
    2
    Boom
     */
    //#watchTermination
  }
}
