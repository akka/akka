/**
 *  Copyright (C) 2012 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.duration

object Scala {
  //#dsl
  import scala.concurrent.duration._

  val fivesec = 5.seconds
  val threemillis = 3.millis
  val diff = fivesec - threemillis
  assert(diff < fivesec)
  val fourmillis = threemillis * 4 / 3 // you cannot write it the other way around
  val n = threemillis / (1 millisecond)
  //#dsl

  //#deadline
  val deadline = 10.seconds.fromNow
  // do something
  val rest = deadline.timeLeft
  //#deadline
}
