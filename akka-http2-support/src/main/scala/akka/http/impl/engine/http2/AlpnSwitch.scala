/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.impl.engine.http2

import java.net.InetSocketAddress
import javax.net.ssl.SSLException

import akka.NotUsed
import akka.http.impl.engine.server.HttpAttributes
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.stream.TLSProtocol.{ SessionBytes, SessionTruncated, SslTlsInbound, SslTlsOutbound }
import akka.stream.scaladsl.{ BidiFlow, Flow, GraphDSL, Keep, Sink, Source }
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream._

object AlpnSwitch {
  type HttpServerBidiFlow = BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, NotUsed]

  def apply(
    chosenProtocolAccessor: () ⇒ String,
    http1Stack:             HttpServerBidiFlow,
    http2Stack:             HttpServerBidiFlow): HttpServerBidiFlow =
    BidiFlow.fromGraph(
      new GraphStage[BidiShape[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest]] {

        // --- outer ports ---
        val netIn = Inlet[SslTlsInbound]("AlpnSwitch.netIn")
        val netOut = Outlet[SslTlsOutbound]("AlpnSwitch.netOut")

        val requestOut = Outlet[HttpRequest]("AlpnSwitch.requestOut")
        val responseIn = Inlet[HttpResponse]("AlpnSwitch.responseIn")
        // --- end of outer ports ---

        val shape: BidiShape[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest] =
          BidiShape(responseIn, netOut, netIn, requestOut)

        def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
          logic ⇒

          // --- inner ports, bound to actual server in install call ---
          val serverDataIn = new SubSinkInlet[SslTlsOutbound]("ServerImpl.netIn")
          val serverDataOut = new SubSourceOutlet[SslTlsInbound]("ServerImpl.netOut")

          val serverRequestIn = new SubSinkInlet[HttpRequest]("ServerImpl.serverRequestIn")
          val serverResponseOut = new SubSourceOutlet[HttpResponse]("ServerImpl.serverResponseOut")
          // --- end of inner ports ---

          override def preStart(): Unit = pull(netIn)

          setHandler(netIn, new InHandler {
            def onPush(): Unit =
              grab(netIn) match {
                case first @ SessionBytes(session, bytes) ⇒
                  val chosen = chosenProtocolAccessor()
                  chosen match {
                    case "h2" ⇒ install(http2Stack, first)
                    case _    ⇒ install(http1Stack, first)
                  }
                case SessionTruncated ⇒ failStage(new SSLException("TLS session was truncated (probably missing a close_notify packet)."))
              }
          })

          private val ignorePull = new OutHandler { def onPull(): Unit = () }
          private val failPush = new InHandler { def onPush(): Unit = throw new IllegalStateException("Wasn't pulled yet") }

          setHandler(netOut, ignorePull)
          setHandler(requestOut, ignorePull)
          setHandler(responseIn, failPush)

          def install(serverImplementation: HttpServerBidiFlow, firstElement: SslTlsInbound): Unit = {
            val networkSide = Flow.fromSinkAndSource(serverDataIn.sink, serverDataOut.source)
            val userSide = Flow.fromSinkAndSource(serverRequestIn.sink, serverResponseOut.source)

            connect(netIn, serverDataOut, Some(firstElement))
            connect(responseIn, serverResponseOut, None)

            connect(serverDataIn, netOut)
            connect(serverRequestIn, requestOut)

            serverImplementation
              .addAttributes(inheritedAttributes) // propagate attributes to "real" server (such as HttpAttributes)
              .join(networkSide)
              .join(userSide)
              .run()(interpreter.subFusingMaterializer)
          }

          // helpers to connect inlets and outlets also binding completion signals of given ports
          def connect[T](in: Inlet[T], out: SubSourceOutlet[T], initialElement: Option[T]): Unit = {
            val propagatePull =
              new OutHandler {
                override def onPull(): Unit = pull(in)
              }

            val firstHandler =
              initialElement match {
                case Some(ele) if out.isAvailable ⇒
                  out.push(ele)
                  propagatePull
                case Some(ele) ⇒
                  new OutHandler {
                    override def onPull(): Unit = {
                      out.push(initialElement.get)
                      out.setHandler(propagatePull)
                    }
                  }
                case None ⇒ propagatePull
              }

            out.setHandler(firstHandler)
            setHandler(in, new InHandler {
              override def onPush(): Unit = out.push(grab(in))

              override def onUpstreamFinish(): Unit = {
                out.complete()
                super.onUpstreamFinish()
              }

              override def onUpstreamFailure(ex: Throwable): Unit = {
                out.fail(ex)
                super.onUpstreamFailure(ex)
              }
            })

            if (out.isAvailable) pull(in) // to account for lost pulls during initialization
          }
          def connect[T](in: SubSinkInlet[T], out: Outlet[T]): Unit = {
            val handler = new InHandler {
              override def onPush(): Unit = push(out, in.grab())
            }

            val outHandler = new OutHandler {
              override def onPull(): Unit = in.pull()
              override def onDownstreamFinish(): Unit = {
                in.cancel()
                super.onDownstreamFinish()
              }
            }
            in.setHandler(handler)
            setHandler(out, outHandler)

            if (isAvailable(out)) in.pull() // to account for lost pulls during initialization
          }
        }
      }
    )
}
