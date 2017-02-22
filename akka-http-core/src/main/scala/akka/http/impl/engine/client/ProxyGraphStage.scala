/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.client

import akka.event.LoggingAdapter
import akka.http.impl.engine.parsing.HttpMessageParser.StateResult
import akka.http.impl.engine.parsing.ParserOutput.{ MessageEnd, NeedMoreData, RemainingBytes, ResponseStart }
import akka.http.impl.engine.parsing.{ HttpHeaderParser, HttpResponseParser, ParserOutput }
import akka.http.scaladsl.model.{ HttpMethods, StatusCodes }
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream.{ Attributes, BidiShape, Inlet, Outlet }
import akka.util.ByteString

object ProxyGraphStage {
  sealed trait State
  // Entry state
  case object Starting extends State

  // State after CONNECT messages has been sent to Proxy and before Proxy responded back
  case object Connecting extends State

  // State after Proxy responded  back
  case object Connected extends State
}

final class ProxyGraphStage(targetHostName: String, targetPort: Int, settings: ClientConnectionSettings, log: LoggingAdapter)
  extends GraphStage[BidiShape[ByteString, ByteString, ByteString, ByteString]] {

  import ProxyGraphStage._

  val bytesIn: Inlet[ByteString] = Inlet("OutgoingTCP.in")
  val bytesOut: Outlet[ByteString] = Outlet("OutgoingTCP.out")

  val sslIn: Inlet[ByteString] = Inlet("OutgoingSSL.in")
  val sslOut: Outlet[ByteString] = Outlet("OutgoingSSL.out")

  override def shape: BidiShape[ByteString, ByteString, ByteString, ByteString] = BidiShape.apply(sslIn, bytesOut, bytesIn, sslOut)

  private val connectMsg = ByteString(s"CONNECT ${targetHostName}:${targetPort} HTTP/1.1\r\nHost: ${targetHostName}\r\n\r\n")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private var state: State = Starting

    val parser = new HttpResponseParser(settings.parserSettings, HttpHeaderParser(settings.parserSettings, log)()) {
      override def handleInformationalResponses = false
      override protected def parseMessage(input: ByteString, offset: Int): StateResult = {
        // hacky, we want in the first branch *all fragments* of the first response
        if (offset == 0) {
          super.parseMessage(input, offset)
        } else {
          if (input.size > offset) {
            emit(RemainingBytes(input.drop(offset)))
          } else {
            emit(NeedMoreData)
          }
          terminate()
        }
      }
    }
    parser.setContextForNextResponse(HttpResponseParser.ResponseContext(HttpMethods.CONNECT, None))

    setHandler(sslIn, new InHandler {
      override def onPush() = {
        state match {
          case Starting ⇒
            throw new IllegalStateException("inlet OutgoingSSL.in unexpectedly pushed in Starting state")
          case Connecting ⇒
            throw new IllegalStateException("inlet OutgoingSSL.in unexpectedly pushed in Connecting state")
          case Connected ⇒
            push(bytesOut, grab(sslIn))
        }
      }

      override def onUpstreamFinish(): Unit = {
        complete(bytesOut)
      }

    })

    setHandler(bytesIn, new InHandler {
      override def onPush() = {
        state match {
          case Starting ⇒
          // that means that proxy had sent us something even before CONNECT to proxy was sent, therefore we just ignore it
          case Connecting ⇒
            val proxyResponse = grab(bytesIn)
            parser.parseBytes(proxyResponse) match {
              case NeedMoreData ⇒
                pull(bytesIn)
              case ResponseStart(_: StatusCodes.Success, _, _, _, _) ⇒
                var pushed = false
                val parseResult = parser.onPull()
                require(parseResult == ParserOutput.MessageEnd, s"parseResult should be MessageEnd but was $parseResult")
                parser.onPull() match {
                  // NeedMoreData is what we emit in overriden `parseMessage` in case input.size == offset
                  case NeedMoreData ⇒
                  case RemainingBytes(bytes) ⇒
                    push(sslOut, bytes) // parser already read more than expected, forward that data directly
                    pushed = true
                  case other ⇒
                    throw new IllegalStateException(s"unexpected element of type ${other.getClass}")
                }
                parser.onUpstreamFinish()

                state = Connected
                if (isAvailable(bytesOut)) {
                  pull(sslIn)
                }
                if (!pushed) {
                  pull(bytesIn)
                }
              case ResponseStart(statusCode, _, _, _, _) ⇒
                failStage(new ProxyConnectionFailedException(s"The HTTPS proxy rejected to open a connection to $targetHostName:$targetPort with status code: $statusCode"))
              case other ⇒
                throw new IllegalStateException(s"unexpected element of type $other")
            }

          case Connected ⇒
            push(sslOut, grab(bytesIn))
        }
      }

      override def onUpstreamFinish(): Unit = complete(sslOut)

    })

    setHandler(bytesOut, new OutHandler {
      override def onPull() = {
        state match {
          case Starting ⇒
            push(bytesOut, connectMsg)
            state = Connecting
          case Connecting ⇒
          // don't need to do anything
          case Connected ⇒
            pull(sslIn)
        }
      }

      override def onDownstreamFinish(): Unit = cancel(sslIn)

    })

    setHandler(sslOut, new OutHandler {
      override def onPull() = {
        pull(bytesIn)
      }

      override def onDownstreamFinish(): Unit = cancel(bytesIn)

    })

  }

}

final case class ProxyConnectionFailedException(msg: String) extends RuntimeException(msg)
