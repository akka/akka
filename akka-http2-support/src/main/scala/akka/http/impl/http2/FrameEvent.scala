package akka.http.impl.http2

sealed trait FrameEvent
sealed trait StreamEvent extends FrameEvent
