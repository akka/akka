/*
 * Copyright (C) 2013-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.duration;

//#import
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.Deadline;
//#import

class Java {
  public void demo() {
    //#dsl
    final Duration fivesec = Duration.create(5, "seconds");
    final Duration threemillis = Duration.create("3 millis");
    final Duration diff = fivesec.minus(threemillis);
    assert diff.lt(fivesec);
    assert Duration.Zero().lt(Duration.Inf());
    //#dsl
    //#deadline
    final Deadline deadline = Duration.create(10, "seconds").fromNow();
    final Duration rest = deadline.timeLeft();
    //#deadline
    rest.toString();
  }
}
