/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.event.slf4j

import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply

/**
 * In Akka typed actors the log event timestamp and thread is correct since logging is done directly using SLF4J, much of the
 * Akka internals however use the classic Akka logging api which is asynchronous and passes over the message bus, and for that
 * source thread and timestamp has special handling, ending up in two MDC entries.
 *
 * This filter adapts classic log events by picking up the `sourceThread` and `akkaTimestampMillis` MDC values
 * and puts those in the respective fields of the log event.
 */
class AkkaLogbackClassicAdaptingFilter extends Filter[LoggingEvent] {

  def decide(event: LoggingEvent): FilterReply = {
    val mdc = event.getMDCPropertyMap

    // FIXME what about thread safety modifying the events? needs to be synchronized?
    if (mdc.containsKey("sourceThread")) {
      // classic emitted event, try to convert to
      event.setThreadName(mdc.get("sourceThread"))

      if (mdc.containsKey("akkaTimestampMillis")) {
        event.setTimeStamp(mdc.get("akkaTimestampMillis").toLong)
      }
    }

    FilterReply.NEUTRAL
  }

}
