/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.sourceorflow

import akka.NotUsed
import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.stream.DelayOverflowStrategy
import akka.stream.scaladsl.DelayStrategy
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString

import scala.concurrent.duration._
import scala.util.Random

/**
 *
 */
object Extrapolate extends App {

  val periodInMillis = 40

  implicit val sys = ActorSystem("25fps-stream")
  // This `networkSource` simulates a client sending frames over the network. There's a
  // stage throttling the elements at 24fps and then a `delayWith` that randomly delays
  // frames simulating network latency and bandwidth limitations (uses buffer of
  // default capacity).
  val networkSource: Source[ByteString, NotUsed] =
    Source
      .fromIterator(() => (1 to 1000000).iterator)
      .throttle(24, 1.second)
      .map(i => ByteString.fromString(s"fakeFrame-$i"))
      .delayWith(
        () =>
          new DelayStrategy[ByteString] {
            override def nextDelay(elem: ByteString): FiniteDuration =
              Random.nextInt(periodInMillis * 10).millis
          },
        DelayOverflowStrategy.dropBuffer)

  val decode: Flow[ByteString, Frame, NotUsed] =
    Flow[ByteString].map(Frame.decode)

  // #extrapolate
  // if upstream is too slow, produce copies of the last frame but grayed out.
  val rateControl = Flow[Frame].extrapolate((frame: Frame) => {
    val grayedOut = frame.withFilter(Gray)
    Iterator.continually(grayedOut)
  }, Some(Frame.blackFrame))

  val videoSource = networkSource.via(decode).via(rateControl)

  // let's create a 25fps stream (a Frame every 40.millis)
  val tickSource = Source.tick(0.seconds, 40.millis, Tick)

  private val videoAt25Fps: Source[Frame, Cancellable] =
    tickSource.zip(videoSource).map(_._2)

  // #extrapolate
  videoAt25Fps.map(_.pixels.utf8String).to(Sink.foreach(println)).run()

  case object Tick

  sealed trait Filter {
    def filter(fr: Frame): Frame
  }
  object Gray extends Filter {
    override def filter(fr: Frame): Frame =
      Frame(ByteString.fromString(s"gray frame!! - ${fr.pixels.utf8String}"))
  }
  case class Frame(pixels: ByteString) {
    def withFilter(f: Filter): Frame = f.filter(this)
  }
  object Frame {
    val blackFrame: Frame = Frame(ByteString.empty)
    def decode(bs: ByteString): Frame = Frame(bs)
  }

}
