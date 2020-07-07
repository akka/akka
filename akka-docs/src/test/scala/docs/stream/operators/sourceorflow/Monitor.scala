/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.sourceorflow

import akka.Done
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.FlowMonitor
import akka.stream.FlowMonitorState
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source

import scala.concurrent.Await
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.Future
import scala.concurrent.duration._

/**
 *
 */
class Monitor {

  implicit val system = ActorSystem("monitor-sample-sys2")
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  // #monitor
  val source: Source[Int, NotUsed] =
    Source.fromIterator(() => Iterator.from(0))

  def printMonitorState(flowMonitor: FlowMonitor[Int]) = {
    flowMonitor.state match {
      case FlowMonitorState.Initialized =>
        println("Stream is initialized but hasn't processed any element")
      case FlowMonitorState.Received(msg) =>
        println(s"Last element processed: $msg")
      case FlowMonitorState.Failed(cause) =>
        println(s"Stream failed with cause $cause")
      case FlowMonitorState.Finished => println(s"Stream completed already")
    }
  }

  val monitoredSource: Source[Int, FlowMonitor[Int]] = source.take(6).throttle(5, 1.second).monitorMat(Keep.right)
  val monitoredStream: (FlowMonitor[Int], Future[Done]) =
    monitoredSource.toMat(Sink.foreach(println))(Keep.both).run()

  val flowMonitor = monitoredStream._1

  // if we peek on the stream early enough it probably won't have processed any element.
  printMonitorState(flowMonitor)
  // #monitor
  // exclude from rendered snippet
  Thread.sleep(500)
  // #monitor

  // ...
  // sometime later, our code has progressed. We can peek in the stream
  // again to see what's the latest element processed
  printMonitorState(flowMonitor)

  // #monitor
  // exclude from rendered snippet
  Await.result(monitoredStream._2, 3.seconds)
  // #monitor
  // #monitor
  // Eventually, the stream completes and if we check the state it reports the streasm finished.
  printMonitorState(flowMonitor)
  // #monitor

  monitoredStream._2.onComplete(_ => system.terminate())

}
