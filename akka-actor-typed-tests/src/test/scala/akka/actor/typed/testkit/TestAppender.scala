package akka.actor.typed.testkit
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase

import scala.collection.mutable.ArrayBuffer

class TestAppender extends AppenderBase[ILoggingEvent] {

  var events = new ArrayBuffer[ILoggingEvent]()

  override def append(event: ILoggingEvent) =
    events += event

}
