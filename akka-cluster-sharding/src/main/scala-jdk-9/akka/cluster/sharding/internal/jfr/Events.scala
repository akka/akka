/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal.jfr
import akka.annotation.InternalApi
import jdk.jfr.{ Category, Enabled, Event, Label, StackTrace, Timespan }

// requires jdk9+ to compile
// for editing these in IntelliJ, open module settings, change JDK dependency to 11 for only this module

/** INTERNAL API */

@InternalApi
@Enabled(true)
@StackTrace(false)
@Category(Array("Akka", "Sharding", "Shard")) @Label("Blocked on remembering entities")
final class BlockedOnRememberUpdate(@Timespan() val duration: Long) extends Event

@InternalApi
@Enabled(true)
@StackTrace(false)
@Category(Array("Akka", "Sharding", "Shard")) @Label("Remembered Entity Operation")
final class RememberedEntityOperation() extends Event
