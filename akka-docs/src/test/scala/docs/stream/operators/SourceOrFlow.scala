/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators

import akka.stream.scaladsl._

//

object SourceOrFlow {

  def logExample(): Unit = {
    //#log
    import akka.stream.Attributes

    //#log

    Flow[String]
    //#log
      .log(name = "myStream")
      .addAttributes(
        Attributes.logLevels(
          onElement = Attributes.LogLevels.Off,
          onFailure = Attributes.LogLevels.Error,
          onFinish = Attributes.LogLevels.Info))
    //#log
  }

  def conflateExample(): Unit = {
    //#conflate
    import scala.concurrent.duration._

    Source
      .cycle(() => List(1, 10, 100, 1000).iterator)
      .throttle(10, per = 1.second) // faster upstream
      .conflate((acc, el) => acc + el) // acc: Int, el: Int
      .throttle(1, per = 1.second) // slow downstream
    //#conflate
  }

  def conflateWithSeedExample(): Unit = {
    //#conflateWithSeed
    import scala.concurrent.duration._

    case class Summed(i: Int) {
      def sum(other: Summed) = Summed(this.i + other.i)
    }

    Source
      .cycle(() => List(1, 10, 100, 1000).iterator)
      .throttle(10, per = 1.second) // faster upstream
      .conflateWithSeed(el => Summed(el))((acc, el) => acc.sum(Summed(el))) // (Summed, Int) => Summed
      .throttle(1, per = 1.second) // slow downstream
    //#conflateWithSeed
  }

  def scanExample(): Unit = {
    import akka.actor.ActorSystem
    import akka.stream.ActorMaterializer

    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()

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
