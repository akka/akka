/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.http.scaladsl.model.{ HttpResponse, HttpRequest }
import akka.stream._

import akka.stream.stage.{ InHandler, GraphStageLogic, GraphStage, OutHandler }

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
      var currentRequest: Option[HttpRequest] = None
      var currentResponse: Option[HttpResponse] = None
      var requestsInFlight = 0
      var requestsProcessed = 0
      def printStatus = println(s"=================================================" +
        s"\nIn flight: $requestsInFlight, processed: $requestsProcessed\n\n")
      /**
       * This is to tell to requestIn that demand exists on requestOut
       */
      setHandler(requestOut, new OutHandler {
        /**
         * Called when the output port has received a pull, and therefore ready to emit an element,
         * i.e. [[GraphStageLogic.push()]] is now allowed to be called on this port.
         */
        override def onPull(): Unit = {
          println("requestOut: onPull")
          pull(requestIn)
          printStatus
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
          println("requestIn: onPush")
          val request = grab(requestIn)
          requestsInFlight += 1
          currentRequest = Some(request)
          if (isAvailable(requestOut)) {
            println("\tPushing incoming request to requestOut")
            push(requestOut, request)
          }
          if (!hasBeenPulled(responseIn)) {
            println("\tPulling from responseIn")
            pull(responseIn)
          }
          printStatus
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
          println("responseIn: onPush")
          val response = grab(responseIn)
          if (response.status.isRedirection) {
            println("\tRedirection!")
            response.headers.find(_.is("location")).foreach(h ⇒ push(requestOut, currentRequest.get.withUri(h.value)))
          } else {
            if (isAvailable(responseOut)) {
              println("\tPushing non-redirection response to responseOut")
              push(responseOut, response)
              requestsInFlight -= 1
              requestsProcessed += 1
            } else {
              println("\tStashing non-redirection response due to lack of demand on responseOut")
              currentResponse = Some(response)
            }

            if (!hasBeenPulled(responseIn)) {
              println("\tPulling from responseIn")
              pull(responseIn)
            }
          }
          printStatus
        }
      })

      setHandler(responseOut, new OutHandler {
        /**
         * Called when the output port has received a pull, and therefore ready to emit an element,
         * i.e. [[GraphStageLogic.push()]] is now allowed to be called on this port.
         */
        override def onPull(): Unit = {
          println("responseOut: onPull")
          if (currentResponse.isDefined) {
            requestsInFlight -= 1
            requestsProcessed += 1
            println("\tPushing to responseOut after redirection")
            push(responseOut, currentResponse.get)
            currentResponse = None
            currentRequest = None
          }
          printStatus
        }

        /**
         * Called when the output port will no longer accept any new elements. After this callback no other callbacks will
         * be called for this port.
         */
        override def onDownstreamFinish(): Unit = cancel(responseIn)
      })
    }
}
