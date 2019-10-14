/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.sourceorflow
import akka.stream.scaladsl.Source

import scala.concurrent.Future

object ScanAsync {
  def scanAsyncExample(): Unit = {
    import akka.actor.ActorSystem

    implicit val system: ActorSystem = ActorSystem()

    //#scanAsync
    val source = Source(1 to 5)
    source.scanAsync(0)((acc, x) => Future.successful(acc + x)).runForeach(println)
    // 0  (= 0)
    // 1  (= 0 + 1)
    // 3  (= 0 + 1 + 2)
    // 6  (= 0 + 1 + 2 + 3)
    // 10 (= 0 + 1 + 2 + 3 + 4)
    // 15 (= 0 + 1 + 2 + 3 + 4 + 5)
    //#scanAsync
  }

}
