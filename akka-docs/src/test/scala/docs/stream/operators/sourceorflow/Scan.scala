/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.sourceorflow
import akka.stream.scaladsl.Source

object Scan {
  def scanExample(): Unit = {
    import akka.actor.ActorSystem

    implicit val system: ActorSystem = ActorSystem()

    //#scan
    val source = Source(1 to 5)
    source.scan(0)((acc, x) => acc + x).runForeach(println)
    // 0  (= 0)
    // 1  (= 0 + 1)
    // 3  (= 0 + 1 + 2)
    // 6  (= 0 + 1 + 2 + 3)
    // 10 (= 0 + 1 + 2 + 3 + 4)
    // 15 (= 0 + 1 + 2 + 3 + 4 + 5)
    //#scan
  }

}
