/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.actor

import akka.actor.Actor
import scala.concurrent.duration._

object TimerDocSpec {
  //#timers
  import akka.actor.Timers

  object MyActor {
    private case object TickKey
    private case object FirstTick
    private case object Tick
    private case object LaterTick
  }

  class MyActor extends Actor with Timers {
    import MyActor._
    timers.startSingleTimer(TickKey, FirstTick, 500.millis)

    def receive = {
      case FirstTick =>
        // do something useful here
        timers.startPeriodicTimer(TickKey, Tick, 1.second)
      case Tick =>
      // do something useful here
    }
  }
  //#timers
}
