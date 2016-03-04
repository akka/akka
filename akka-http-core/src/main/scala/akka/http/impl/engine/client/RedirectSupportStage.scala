package akka.http.impl.engine.client

import akka.http.scaladsl.model.{ HttpResponse, HttpRequest }
import akka.stream._
import akka.stream.scaladsl.{ Flow, GraphDSL, BidiFlow }

/**
 * Created by roman on 01.03.16.
 */
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
  override def shape: BidiShape[HttpRequest, HttpRequest, HttpResponse, HttpResponse] = BidiShape(requestIn, requestOut, responseIn, responseOut)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      var currentRequest: Option[HttpRequest] = None
      var currentResponse: Option[HttpResponse] = None
      /**
       * This is to tell to requestIn that demand exists on requestOut
       */
      setHandler(requestOut, new OutHandler {
        /**
         * Called when the output port has received a pull, and therefore ready to emit an element, i.e. [[GraphStageLogic.push()]]
         * is now allowed to be called on this port.
         */
        override def onPull(): Unit = {
          if (!hasBeenPulled(requestIn)) {
            pull(requestIn)
          }
        }
      })

      setHandler(requestIn, new InHandler {
        /**
         * Called when the input port has a new element available. The actual element can be retrieved via the
         * [[GraphStageLogic.grab()]] method.
         */
        override def onPush(): Unit = {
          val request = grab(requestIn)
          currentRequest = Some(request)
          push(requestOut, request)
        }
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
          val response = grab(responseIn)
          if (response.status.isRedirection) {
            response.headers.find(_.is("location")).foreach(h ⇒ push(requestOut, currentRequest.get.withUri(h.value)))
          } else {
            if (isAvailable(responseOut)) {
              push(responseOut, response)
            } else {
              currentResponse = Some(response)
            }
          }
        }
      })

      setHandler(responseOut, new OutHandler {
        /**
         * Called when the output port has received a pull, and therefore ready to emit an element, i.e. [[GraphStageLogic.push()]]
         * is now allowed to be called on this port.
         */
        override def onPull(): Unit = {
          if (currentResponse.isDefined) {
            push(responseOut, currentResponse.get)
            currentResponse = None
            currentRequest = None
          }
        }
      })
    }
}
