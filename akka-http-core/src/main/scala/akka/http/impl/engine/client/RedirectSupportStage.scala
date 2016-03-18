/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.http.scaladsl.model.{ HttpResponse, HttpRequest }
import akka.stream._

import akka.stream.stage.{ InHandler, GraphStageLogic, GraphStage, OutHandler }

import scala.language.higherKinds

class RedirectSupportStage extends GraphStage[BidiShape[HttpRequest, HttpRequest, HttpResponse, HttpResponse]] {
  /**
   * in1 is where the original messages come from
   */
  val requestIn: Inlet[HttpRequest] = Inlet("Input1")

  /**
   * in2 is the responses to original messages, that might not be forwarded, but retried
   */
  val responseIn: Inlet[HttpResponse] = Inlet("Input2")

  /**
   * out1 is where input messages go to
   */
  val requestOut: Outlet[HttpRequest] = Outlet("Output1")

  /**
   * out2 is where resulting messages is going to, potentially after retry
   */
  val responseOut: Outlet[HttpResponse] = Outlet("Output2")

  /**
   * The shape of a graph is all that is externally visible: its inlets and outlets.
   */
  override def shape: BidiShape[HttpRequest, HttpRequest, HttpResponse, HttpResponse] =
    BidiShape(requestIn, requestOut, responseIn, responseOut)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {

      trait OpOutPrinter[F, _ <: Outlet[F]] {
        def print(t: F): Unit
      }

      trait OpInPrinter[T <: Inlet[_]] {
        def print(): Unit
      }

      def printy(s: String) = ()//println(s)

      object OpOutPrinter {
        implicit val RequestOutPrinterReq = new OpOutPrinter[HttpRequest, Outlet[HttpRequest]] {
          override def print(t: HttpRequest): Unit = printy(s"\tpushing $t to requestOut")
        }
        implicit val ResponseOutPrinterResp = new OpOutPrinter[HttpResponse, Outlet[HttpResponse]] {
          override def print(t: HttpResponse): Unit = printy(s"\tpushing $t to responseOut")
        }
      }

      object OpInPrinter {
        implicit val RequestInPrinterReq = new OpInPrinter[Inlet[HttpRequest]] {
          override def print(): Unit = {
            printy("\tpulling from requestIn")
          }
        }
        implicit val ResponseInPrinterResp = new OpInPrinter[Inlet[HttpResponse]] {
          override def print(): Unit = {
            printy("\tpulling from responseIn")
          }
        }
      }

      import OpOutPrinter._
      import OpInPrinter._

      def doPush[T](outlet: Outlet[T], t: T)(implicit ev: OpOutPrinter[T, Outlet[T]]) = {
        push(outlet, t)
        ev.print(t)
      }

      def doPull[T](inlet: Inlet[T])(implicit ev: OpInPrinter[Inlet[T]]) = {
        if (!isClosed(inlet)) {
          pull(inlet)
        }
        ev.print()
      }

      var inFlight = Vector.empty[HttpRequest]
      var unconsumedResponses = Vector.empty[HttpResponse]
      var unconsumedRequests = Vector.empty[HttpRequest]
      var requestsProcessed = 0
      def printStatus() = printy(s"=================================================" +
        s"\nIn flight: ${inFlight.length}, processed: $requestsProcessed\n\n")

      /**
       * Invoked before any external events are processed, at the startup of the stage.
       */
      override def preStart(): Unit = {
        printy("preStart")
        doPull(requestIn)
        printStatus()
      }

      /**
       * This is to tell to requestIn that demand exists on requestOut
       */
      setHandler(requestOut, new OutHandler {
        /**
         * Called when the output port has received a pull, and therefore ready to emit an element,
         * i.e. [[GraphStageLogic.push()]] is now allowed to be called on this port.
         */
        override def onPull(): Unit = {
          printy("requestOut: onPull")
          unconsumedRequests = unconsumedRequests.headOption match {
            case Some(h) => doPush(requestOut, h); doPull(responseIn); unconsumedRequests.tail
            case None => unconsumedRequests
          }
          printStatus()
        }

        /**
         * Called when the output port will no longer accept any new elements. After this callback no other callbacks
         * will be called for this port.
         */
        override def onDownstreamFinish(): Unit = super.onDownstreamFinish()
      })

      setHandler(requestIn, new InHandler {
        /**
         * Called when the input port has a new element available. The actual element can be retrieved via the
         * [[GraphStageLogic.grab()]] method.
         */
        override def onPush(): Unit = {
          printy("requestIn: onPush")
          val r = grab(requestIn)
          if (isAvailable(requestOut)) {
            doPush(requestOut, r)
            doPull(responseIn)
          } else {
            unconsumedRequests = unconsumedRequests :+ r
          }
          inFlight = inFlight :+ r
          printStatus()
        }

        /**
         * Called when the input port is finished. After this callback no other callbacks will be called for this port.
         */
        override def onUpstreamFinish(): Unit = complete(requestOut)
      })

      /**
       * Response is available. Check if it is redirect and act accordingly.
       */
      setHandler(responseIn, new InHandler {
        /**
         * Called when the input port has a new element available. The actual element can be retrieved via the
         * [[GraphStageLogic.grab()]] method.
         */
        override def onPush(): Unit = {
          printy("responseIn: onPush")
          val r = grab(responseIn)
          if (r.status.isRedirection) {
            val location = r.headers.find(_.is("location"))
            if (location.isDefined) {
              val redirect = inFlight.head.withUri(location.get.value) // FIXME not all headers should be copied
              inFlight = inFlight.tail :+ redirect
              if (isAvailable(requestOut)) {
                doPush(requestOut, redirect)
                doPull(responseIn)
              } else {
                unconsumedRequests = unconsumedRequests :+ redirect
              }
            }
          } else {
            requestsProcessed += 1
            inFlight = inFlight.tail
            if (isAvailable(responseOut)) {
              doPush(responseOut, r)
              doPull(requestIn)
            } else {
              unconsumedResponses = unconsumedResponses :+ r
            }
          }
          printStatus()
        }
      })

      setHandler(responseOut, new OutHandler {
        /**
         * Called when the output port has received a pull, and therefore ready to emit an element,
         * i.e. [[GraphStageLogic.push()]] is now allowed to be called on this port.
         */
        override def onPull(): Unit = {
          printy("responseOut: onPull")
          unconsumedResponses = unconsumedResponses.headOption match {
            case Some(h) => doPush(responseOut, h); unconsumedResponses.tail
            case None => unconsumedResponses
          }
          printStatus()
        }

        /**
         * Called when the output port will no longer accept any new elements. After this callback no other callbacks will
         * be called for this port.
         */
        override def onDownstreamFinish(): Unit = cancel(responseIn)
      })

    }
}
