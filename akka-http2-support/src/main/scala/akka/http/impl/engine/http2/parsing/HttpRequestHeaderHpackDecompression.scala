/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2.parsing

import akka.event.Logging
import akka.http.impl.engine.http2._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.http2.Http2StreamIdHeader
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.stage._
import akka.stream._
import com.twitter.hpack.HeaderListener
import HttpRequestHeaderHpackDecompression._

import scala.collection.immutable
import akka.dispatch.ExecutionContexts
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.util.ByteString

/** INTERNAL API */
private[http2] final class HttpRequestHeaderHpackDecompression extends GraphStage[FlowShape[Http2SubStream, HttpRequest]] {
  private final val ColonByte = ':'.toByte

  val streamIn = Inlet[Http2SubStream](Logging.simpleName(this) + ".streamIn")
  val requestOut = Outlet[HttpRequest](Logging.simpleName(this) + ".requestOut")
  override val shape = FlowShape.of(streamIn, requestOut)

  // format: OFF
  override def createLogic(inheritedAttributes: Attributes) =
    new GraphStageLogic(shape)
      with InHandler
      with HeaderListener with BufferedOutletSupport {
      // format: ON

      /** While we're pulling a SubStreams frames, we should not pass through completion */
      private var pullingSubStreamFrames = false
      /** If the outer upstream has completed while we were pulling substream frames, we should complete it after we emit the request. */
      private var completionPending = false

      private[this] def resetBeingBuiltRequest(http2SubStream: Http2SubStream): Unit = {
        val streamId = http2SubStream.streamId

        beingBuiltRequest =
          HttpRequest(headers = immutable.Seq(Http2StreamIdHeader(streamId)))
            .withProtocol(HttpProtocols.`HTTP/2.0`)

        val entity =
          if (http2SubStream.initialFrame.endStream) {
            HttpEntity.Strict(ContentTypes.NoContentType, ByteString.empty)
          } else {
            // FIXME fix the size and entity type according to info from headers
            val data = http2SubStream.frames.completeAfter(_.endStream).map(_.payload)
            HttpEntity.Default(ContentTypes.NoContentType, Long.MaxValue, data) // FIXME that 1 is a hack, since it must be positive, and we're awaiting a real content length...
          }

        beingBuiltRequest = beingBuiltRequest.copy(entity = entity)
      }

      private[this] var beingBuiltRequest: HttpRequest = _ // TODO replace with "RequestBuilder" that's more efficient

      val decoder = new com.twitter.hpack.Decoder(maxHeaderSize, maxHeaderTableSize)

      // buffer outgoing requests if necessary (total number limited by SETTINGS_MAX_CONCURRENT_STREAMS)
      val bufferedRequestOut = new BufferedOutlet(requestOut)

      setHandler(streamIn, this)

      override def preStart(): Unit = pull(streamIn)

      override def onPush(): Unit = {
        val httpSubStream = grab(streamIn)
        // no backpressure (limited by SETTINGS_MAX_CONCURRENT_STREAMS)
        pull(streamIn)

        resetBeingBuiltRequest(httpSubStream)
        processFrame(httpSubStream.initialFrame)

        if (httpSubStream.initialFrame.endStream) {
          // we know frames should be empty here.
          // but for sanity lets kill that stream anyway I guess (at least for now)
          requireRemainingStreamEmpty(httpSubStream)
        }
      }

      // this is invoked synchronously from decoder.decode()
      override def addHeader(name: Array[Byte], value: Array[Byte], sensitive: Boolean): Unit = {
        // FIXME wasteful :-(
        val nameString = new String(name)
        val valueString = new String(value)

        // FIXME lookup here must be optimised
        if (name.head == ColonByte) {
          nameString match {
            case ":method" ⇒
              val method = HttpMethods.getForKey(valueString)
                .getOrElse(throw new IllegalArgumentException(s"Unknown HttpMethod! Was: '$valueString'."))

              // FIXME only copy if value has changed to avoid churning allocs
              beingBuiltRequest = beingBuiltRequest.copy(method = method)

            case ":path" ⇒
              // FIXME only copy if value has changed to avoid churning allocs
              beingBuiltRequest = beingBuiltRequest.copy(uri = beingBuiltRequest.uri.withPath(Uri.Path(valueString)))

            case ":authority" ⇒
              beingBuiltRequest = beingBuiltRequest.copy(uri = beingBuiltRequest.uri.withAuthority(Uri.Authority.parse(valueString)))

            case ":scheme" ⇒
              beingBuiltRequest = beingBuiltRequest.copy(uri = beingBuiltRequest.uri.withScheme(valueString))

            // TODO handle all special headers

            case unknown ⇒
              throw new Exception(s": prefixed header should be emitted well-typed! Was: '${new String(unknown)}'. This is a bug.")
          }
        } else {
          nameString match {
            case "content-type" ⇒

              val entity = beingBuiltRequest.entity
              ContentType.parse(valueString) match {
                case Right(ct) ⇒
                  val len = entity.contentLengthOption.getOrElse(0L)
                  // FIXME instead of putting in random 1, this should become a builder, that emits the right type of entity (with known size or not)
                  val newEntity =
                    if (len == 0) HttpEntity.Strict(ct, ByteString.empty) // HttpEntity.empty(entity.contentType)
                    else HttpEntity.Default(ct, len, entity.dataBytes)

                  beingBuiltRequest = beingBuiltRequest.copy(entity = newEntity) // FIXME not quite correct still
                case Left(errorInfos) ⇒ throw new ParsingException(errorInfos.head)
              }

            case "content-length" ⇒
              val entity = beingBuiltRequest.entity
              val len = java.lang.Long.parseLong(valueString)
              val newEntity =
                if (len == 0) HttpEntity.Strict(entity.contentType, ByteString.empty) // HttpEntity.empty(entity.contentType)
                else HttpEntity.Default(entity.contentType, len, entity.dataBytes)

              beingBuiltRequest = beingBuiltRequest.copy(entity = newEntity) // FIXME not quite correct still

            case _ ⇒
              // other headers we simply expose as RawHeader
              // FIXME handle all typed headers
              beingBuiltRequest = beingBuiltRequest.addHeader(RawHeader(nameString, new String(value)))
          }
        }
      }

      override def onUpstreamFinish(): Unit = {
        if (pullingSubStreamFrames) {
          // we're currently pulling Frames out of the SubStream, thus we should not shut-down just yet
          completionPending = true // TODO I think we don't need this, can just rely on isClosed(in)?
        } else {
          // we've emitted all there was to emit, and can complete this stage
          completeStage()
        }
      }

      // TODO needs cleanup?
      private def processFrame(frame: StreamFrameEvent): Unit = {
        frame match {
          case h: HeadersFrame ⇒
            require(h.endHeaders, s"${getClass.getSimpleName} requires a complete HeadersFrame, the stage before it should concat incoming continuation frames into it.")

            // TODO optimise
            val is = ByteStringInputStream(h.headerBlockFragment)
            decoder.decode(is, this) // this: HeaderListener (invoked synchronously)
            decoder.endHeaderBlock()

            emit(requestOut, beingBuiltRequest)

          case _ ⇒
            throw new UnsupportedOperationException(s"Not implemented to handle $frame! TODO / FIXME for impl.")
        }
      }

      // TODO if we can inspect it and it's really empty we don't need to materialize, for safety otherwise we cancel that stream
      private def requireRemainingStreamEmpty(httpSubStream: Http2SubStream): Unit = {
        if (httpSubStream.frames == Source.empty) ()
        else {
          // FIXME less aggresive once done with PoC
          implicit val ec = ExecutionContexts.sameThreadExecutionContext // ok, we'll just block and blow up, good.
          httpSubStream.frames.runWith(Sink.foreach(t ⇒ interpreter.log.warning("Draining element: " + t)))(interpreter.materializer)
            .map(_ ⇒ throw new IllegalStateException("Expected no more frames, but source was NOT empty! " +
              s"Draining the remaining frames, from: ${httpSubStream.frames}"))
        }
      }
    }

}

/** INTERNAL API */
private[http2] object HttpRequestHeaderHpackDecompression {

  implicit class CompleteAfterSource[T](val s: Source[T, _]) extends AnyVal {
    /**
     * Passes through elements until the test returns `true`.
     * The element that triggered this is then passed through, and *after* that completion is signalled.
     */
    def completeAfter(test: T ⇒ Boolean) =
      s.via(new SimpleLinearGraphStage[T] {
        override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {
          override def onPush(): Unit = {
            val el = grab(in)
            if (test(el)) emit(out, el, () ⇒ completeStage())
            else push(out, el)
          }
          override def onPull(): Unit = pull(in)
          setHandlers(in, out, this)
        }
      })
  }

  final val maxHeaderSize = 4096
  final val maxHeaderTableSize = 4096
}
