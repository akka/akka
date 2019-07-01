/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, BidiShape, FlowShape, Graph, Inlet, Outlet}
import com.typesafe.config.ConfigFactory

import scala.collection.immutable
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Success, Try}

object RetryFlow {

  def withBackoff[In, Out, State, Mat](
      parallelism: Int,
      minBackoff: FiniteDuration,
      maxBackoff: FiniteDuration,
      randomFactor: Double,
      flow: Flow[(In, State), (Try[Out], State), Mat])(
      retryWith: (Try[Out], State) => Option[immutable.Iterable[(In, State)]])
      : Graph[FlowShape[(In, State), (Try[Out], State)], Mat] = {
    GraphDSL.create(flow) { implicit b => origFlow =>
      import GraphDSL.Implicits._

      println(minBackoff)
      println(maxBackoff)
      println(randomFactor)

      val retry = b.add(new RetryFlowCoordinator[In, State, Out](10, parallelism, retryWith))

      retry.out2 ~> origFlow ~> retry.in2

      FlowShape(retry.in1, retry.out1)
    }
  }

}

private class RetryFlowCoordinator[In, State, Out](
    retriesLimit: Long,
    bufferLimit: Long,
    retryWith: (Try[Out], State) => Option[immutable.Iterable[(In, State)]])
    extends GraphStage[BidiShape[(In, State), (Try[Out], State), (Try[Out], State), (In, State)]] {

  val in1 = Inlet[(In, State)]("RetryFlow.ext.in")
  val out1 = Outlet[(Try[Out], State)]("RetryFlow.ext.out")
  val in2 = Inlet[(Try[Out], State)]("RetryFlow.int.in")
  val out2 = Outlet[(In, State)]("RetryFlow.int.out")

  override val shape = BidiShape[(In, State), (Try[Out], State), (Try[Out], State), (In, State)](in1, out1, in2, out2)

  override def createLogic(attributes: Attributes) = new GraphStageLogic(shape) {
    var numElementsInCycle = 0
    val queueRetries = scala.collection.mutable.Queue.empty[(In, State)]
    val queueOut1 = scala.collection.mutable.Queue.empty[(Try[Out], State)]
    lazy val timeout =
      Duration.fromNanos(ConfigFactory.load().getDuration("akka.stream.contrib.retry-timeout").toNanos)

    setHandler(
      in1,
      new InHandler {
        override def onPush() = {
          val is = grab(in1)
          if (isAvailable(out2)) {
            push(out2, is)
            numElementsInCycle += 1
          } else queueRetries.enqueue(is)
        }

        override def onUpstreamFinish() =
          if (numElementsInCycle == 0 && queueRetries.isEmpty) {
            if (queueOut1.isEmpty) completeStage()
            else emitMultiple(out1, queueOut1.iterator, () => completeStage())
          }
      })

    setHandler(
      out1,
      new OutHandler {
        override def onPull() =
          // prioritize pushing queued element if available
          if (queueOut1.isEmpty) pull(in2)
          else push(out1, queueOut1.dequeue())
      })

    setHandler(
      in2,
      new InHandler {
        override def onPush() = {
          numElementsInCycle -= 1
          grab(in2) match {
            case s @ (_: Success[Out], _) => pushAndCompleteIfLast(s)
            case failure =>
              retryWith.tupled(failure).fold(pushAndCompleteIfLast(failure)) {
                xs =>
                  if (xs.size + queueRetries.size > retriesLimit)
                    failStage(new IllegalStateException(
                      s"Queue limit of $retriesLimit has been exceeded. Trying to append ${xs.size} elements to a queue that has ${queueRetries.size} elements."))
                  else {
                    xs.foreach(queueRetries.enqueue(_))
                    if (queueRetries.isEmpty) {
                      if (isClosed(in1) && queueOut1.isEmpty) completeStage()
                      else pull(in2)
                    } else {
                      pull(in2)
                      if (isAvailable(out2)) {
                        val elem = queueRetries.dequeue()
                        push(out2, elem)
                        numElementsInCycle += 1
                      }
                    }
                  }
              }
          }
        }
      })

    def pushAndCompleteIfLast(elem: (Try[Out], State)): Unit = {
      if (isAvailable(out1) && queueOut1.isEmpty) {
        push(out1, elem)
      } else if (queueOut1.size + 1 > bufferLimit) {
        failStage(new IllegalStateException(
          s"Buffer limit of $bufferLimit has been exceeded. Trying to append 1 element to a buffer that has ${queueOut1.size} elements."))
      } else {
        queueOut1.enqueue(elem)
      }

      if (isClosed(in1) && queueRetries.isEmpty && numElementsInCycle == 0 && queueOut1.isEmpty) {
        completeStage()
      }
    }

    setHandler(
      out2,
      new OutHandler {
        override def onPull() =
          if (queueRetries.isEmpty) {
            if (!hasBeenPulled(in1) && !isClosed(in1)) {
              pull(in1)
            }
          } else {
            push(out2, queueRetries.dequeue())
            numElementsInCycle += 1
          }

        override def onDownstreamFinish() =
          materializer.scheduleOnce(timeout, new Runnable {
            override def run() =
              getAsyncCallback[Unit] { _ =>
                if (!isClosed(in2)) {
                  failStage(
                    new IllegalStateException(
                      s"inner flow canceled only upstream, while downstream remain available for $timeout"))
                }
              }.invoke(())
          })
      })
  }
}
