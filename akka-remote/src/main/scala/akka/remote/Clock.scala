package akka.remote

import java.util.concurrent.TimeUnit._

object Clock {

  implicit val defaultClock = new Clock {
    def apply() = NANOSECONDS.toMillis(System.nanoTime)
  }
}

trait Clock extends (() ⇒ Long)
