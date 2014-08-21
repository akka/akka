/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

object ReactiveStreamsConstants {

  final val CanNotSubscribeTheSameSubscriberMultipleTimes =
    "can not subscribe the same subscriber multiple times (see reactive-streams specification, rules 1.10 and 2.12)"

  final val SupportsOnlyASingleSubscriber =
    "only supports one subscriber (which is allowed, see reactive-streams specification, rule 1.12)"

  final val NumberOfElementsInRequestMustBePositiveMsg =
    "The number of requested elements must be > 0 (see reactive-streams specification, rule 3.9)"

  final val TotalPendingDemandMustNotExceedLongMaxValue =
    "Total pending demand MUST NOT be > `java.lang.Long.MAX_VALUE` (see reactive-streams specification, rule 3.17)"

}
