package akka.actor.typed.testkit
import java.util.concurrent.locks.ReentrantLock

import ch.qos.logback.classic.spi.ILoggingEvent
import org.slf4j.LoggerFactory
import org.slf4j.event.LoggingEvent

object AppenderInterceptor {

  val logger = LoggerFactory.getLogger("akka.actor.typed.scaladsl.ActorLoggingSpec")
  val root = logger.asInstanceOf[ch.qos.logback.classic.Logger]
  val myAppender = root.getAppender("INTERCEPTOR").asInstanceOf[TestAppender]
  def events:() =>  Seq[LoggingEvent] = () => myAppender.events.map { each =>
    each match {
      //TODO implement also for log4j, ILoggingEvent is logback specific
      case event: ILoggingEvent => {
        new Sl4jLoggingEvent(
          event.getLevel(),
          event.getMessage(),
          event.getLoggerName(),
          event.getTimeStamp,
          event.getArgumentArray,
        )
      }
    }
  }

}
