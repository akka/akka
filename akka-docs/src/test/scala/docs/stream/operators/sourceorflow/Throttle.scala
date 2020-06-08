package docs.stream.operators.sourceorflow

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ThrottleMode
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source

import scala.concurrent.duration._

/**
 * 
 */
object Throttle extends App {

  implicit val sys = ActorSystem("25fps-stream")

  val frameSource: Source[Int, NotUsed] =
    Source.fromIterator(() => Iterator.from(0))

  // #throttle
  val framesPerSecond = 24

  // val frameSource: Source[Frame,_]
  val videoThrottling = frameSource
    .throttle(
      framesPerSecond,
      1.second,
      framesPerSecond * 30, // maximumBurst
      ThrottleMode.shaping
    )
  // serialize `Frame` and send over the network.
  // #throttle

  videoThrottling
    .to(Sink.foreach(println))
    .run()
}

object ThrottleCommon{

  // used in ThrottleJava
  case class Frame(i: Int)

}
