package spray.can

import spray.http.{ ByteStringRendering, HttpResponsePart }
import akka.util.ByteString
import spray.can.rendering.{ ResponseRenderingComponent, ResponsePartRenderingContext }
import akka.event.{ NoLogging }

object Renderer extends ResponseRenderingComponent {
  def serverHeaderValue: String = "streamy"
  def chunklessStreaming: Boolean = true

  def renderPart(part: HttpResponsePart): ByteString = {
    val rendering = new ByteStringRendering(1024) //new HttpDataRendering(settings.responseHeaderSizeHint)
    val ctx = ResponsePartRenderingContext(part)
    val close = renderResponsePartRenderingContext(rendering, ctx, NoLogging)
    //println(s"Rendering: $part close: $close")
    // FIXME: somehow use "close"
    rendering.get
  }
}
