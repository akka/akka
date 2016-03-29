/**
  * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
  */

package akka.http.impl.engine.client

import akka.http.impl.engine.client.RedirectSupportStage.RedirectMapper
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.ClientAutoRedirectSettings
import akka.http.scaladsl.settings.ClientAutoRedirectSettings.HeadersForwardMode.{All, Only, Zero}
import akka.stream._
import akka.stream.scaladsl.BidiFlow
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}

import scala.collection.immutable
import scala.language.higherKinds
import scala.util.control.NoStackTrace

object RedirectSupportStage {

  type RedirectMapper = (ClientAutoRedirectSettings, HttpRequest, HttpResponse) => Option[HttpRequest]

  case class DefaultRedirectMapper() extends RedirectMapper {

    def inferMethod(req: HttpRequest, resp: HttpResponse): Option[HttpMethod] = {
      import HttpMethods._
      import StatusCodes._
      val m = req.method
      resp.status match {
        case MovedPermanently | Found | TemporaryRedirect | PermanentRedirect if m == GET || m == HEAD => Some(m)
        case SeeOther => Some(GET)
        case _ => None
      }
    }

    def sameOrigin(u1: Uri, u2: Uri): Boolean = {
      if (u2.isRelative) true
      else if (u1.isAbsolute && u2.isAbsolute && u1.authority == u2.authority) true
      else false
    }

    def apply(s: ClientAutoRedirectSettings, req: HttpRequest, resp: HttpResponse): Option[HttpRequest] = {
      if (resp.status.isRedirection) {
        inferMethod(req, resp).flatMap { method =>
          resp.headers.find(_.is("location")).flatMap(location => {
            val item = if (sameOrigin(req.uri, Uri(location.value))) s.sameOrigin else s.crossOrigin
            if (item.allow) {
              val headers = item.forwardHeaders match {
                case All => req.headers
                case Zero => Nil
                case Only(hs) => req.headers.filter(h => hs.exists(h.is))
              }
              Some(req.withUri(location.value).withHeaders(headers).withMethod(method))
            } else {
              None
            }
          })
        }
      } else {
        None
      }
    }
  }

  case class InfiniteRedirectLoopException(loop: immutable.Seq[Uri])
    extends RuntimeException("Infinite redirect loop detected, breaking.")
      with NoStackTrace

  trait OpOutPrinter[F, _ <: Outlet[F]] {
    def print(t: F): Unit
  }

  trait OpInPrinter[T <: Inlet[_]] {
    def print(): Unit
  }

  def printy(s: Any) = ()//println(s)

  implicit val RequestOutPrinterReq = new OpOutPrinter[HttpRequest, Outlet[HttpRequest]] {
    override def print(t: HttpRequest): Unit = printy(s"\tpushing $t to requestOut")
  }
  implicit val ResponseOutPrinterResp = new OpOutPrinter[HttpResponse, Outlet[HttpResponse]] {
    override def print(t: HttpResponse): Unit = printy(s"\tpushing $t to responseOut")
  }
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

  def apply(settings: ClientAutoRedirectSettings) =
    BidiFlow.fromGraph(new RedirectSupportStage(settings, DefaultRedirectMapper()))

  def apply(settings: ClientAutoRedirectSettings, redirectMapper: RedirectMapper) =
    BidiFlow.fromGraph(new RedirectSupportStage(settings, redirectMapper))
}

/**
  * A BidiShape that sits on top of OutgoingConnectionBlueprint and routes the redirect responses back to the
  * downstream outgoing connection if [[redirectMapper]] allows, or upstream otherwise
  *
  * @param settings       Redirect configuration
  * @param redirectMapper returns Some(HttpRequest) with the redirect HttpRequest if automatic redirect must be
  *                       performed, or None if received response must go directly upstream
  */
class RedirectSupportStage(settings: ClientAutoRedirectSettings, redirectMapper: RedirectMapper)
  extends GraphStage[BidiShape[HttpRequest, HttpRequest, HttpResponse, HttpResponse]] {
  /**
    * in1 is where the original messages come from
    */
  val requestIn: Inlet[HttpRequest] = Inlet("RedirectSupportStage.requestIn")

  /**
    * in2 is the responses to original messages. If it's a redirect, its not forwarded upstream, but goes downstream
    */
  val responseIn: Inlet[HttpResponse] = Inlet("RedirectSupportStage.responseIn")

  /**
    * out1 is where input messages go to
    */
  val requestOut: Outlet[HttpRequest] = Outlet("RedirectSupportStage.requestOut")

  /**
    * out2 is where resulting messages is going to, potentially after redirect
    */
  val responseOut: Outlet[HttpResponse] = Outlet("RedirectSupportStage.responseOut")

  /**
    * The shape of a graph is all that is externally visible: its inlets and outlets.
    */
  override def shape: BidiShape[HttpRequest, HttpRequest, HttpResponse, HttpResponse] =
    BidiShape(requestIn, requestOut, responseIn, responseOut)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {

      import RedirectSupportStage.{InfiniteRedirectLoopException, OpInPrinter, OpOutPrinter, printy}

      def doPush[T](outlet: Outlet[T], t: T)(implicit ev: OpOutPrinter[T, Outlet[T]]) = {
        push(outlet, t)
        ev.print(t)
      }

      def doPull[T](inlet: Inlet[T])(implicit ev: OpInPrinter[Inlet[T]]): Boolean = {
        ev.print()
        val inputOpened = !isClosed(inlet)
        if (inputOpened) {
          pull(inlet)
        }
        inputOpened
      }

      var inFlight = Vector.empty[HttpRequest]
      var loopDetector = Map.empty[HttpRequest, List[Uri]]
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
          printStatus()
        }

        /**
          * Called when the output port will no longer accept any new elements. After this callback no other callbacks
          * will be called for this port.
          */
        override def onDownstreamFinish(): Unit = {
          printy("requestOut: onDownstreamFinish")
          cancel(requestIn)
        }

      })

      setHandler(requestIn, new InHandler {
        /**
          * Called when the input port has a new element available. The actual element can be retrieved via the
          * [[GraphStageLogic.grab()]] method.
          */
        override def onPush(): Unit = {
          printy("requestIn: onPush")
          val r = grab(requestIn)
          emit(requestOut, r, () => doPull(responseIn))
          inFlight = inFlight :+ r
          loopDetector += (r -> List(r.uri))
          printStatus()
        }

        /**
          * Called when the input port is finished. After this callback no other callbacks will be called for this port.
          */
        override def onUpstreamFinish(): Unit = {
          printy("requestIn: onUpstreamFinish")
          if (inFlight.isEmpty) {
            complete(requestOut)
          }
        }

        /**
          * Called when the input port has failed. After this callback no other callbacks will be called for this port.
          */
        override def onUpstreamFailure(ex: Throwable): Unit = {
          printy("requestIn: onUpstreamFailure")
          fail(requestOut, ex)
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
          printy("responseIn: onPush")
          val response = grab(responseIn)
          redirectMapper(settings, inFlight.head, response) match {
            case Some(redirect) =>
              printy(loopDetector.toString())
              val loops = loopDetector(inFlight.head)
              if (loops.contains(redirect.uri)) {
                throw InfiniteRedirectLoopException((redirect.uri :: loops).reverse)
              } else {
                loopDetector -= inFlight.head
                loopDetector += (redirect -> (redirect.uri :: loops))
                inFlight = inFlight.tail :+ redirect
                printy(redirect)
                emit(requestOut, redirect, () => doPull(responseIn))
              }
            case None =>
              requestsProcessed += 1
              loopDetector -= inFlight.head
              inFlight = inFlight.tail
              emit(responseOut, response, () => pullRequestIn() )
          }
          printStatus()
        }

        def pullRequestIn(): Unit = {
          if (!doPull(requestIn) && inFlight.isEmpty) completeStage()
        }

        /**
          * Called when the input port is finished. After this callback no other callbacks will be called for this port.
          */
        override def onUpstreamFinish(): Unit = {
          printy("responseIn: onUpstreamFinish")
          complete(responseOut)
        }

        /**
          * Called when the input port has failed. After this callback no other callbacks will be called for this port.
          */
        override def onUpstreamFailure(ex: Throwable): Unit = {
          printy("responseIn: onUpstreamFailure")
          fail(responseOut, ex)
        }
      })

      setHandler(responseOut, new OutHandler {
        /**
          * Called when the output port has received a pull, and therefore ready to emit an element,
          * i.e. [[GraphStageLogic.push()]] is now allowed to be called on this port.
          */
        override def onPull(): Unit = {
          printy("responseOut: onPull")
          printStatus()
        }

        /**
          * Called when the output port will no longer accept any new elements. After this callback no other callbacks
          * will be called for this port.
          */
        override def onDownstreamFinish(): Unit = {
          printy("responseOut: onDownstreamFinish")
          cancel(responseIn)
        }
      })

    }
}