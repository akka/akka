/*
 * Copyright (C) 2020-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.sourceorflow

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source

object GroupBy {

  def groupBySourceExample(): Unit = {
    implicit val system: ActorSystem = ???
    //#groupBy
    Source(1 to 10)
      .groupBy(maxSubstreams = 2, _ % 2 == 0) // create two sub-streams with odd and even numbers
      .reduce(_ + _) // for each sub-stream, sum its elements
      .mergeSubstreams // merge back into a stream
      .runForeach(println)
    //25
    //30
    //#groupBy
  }

}
