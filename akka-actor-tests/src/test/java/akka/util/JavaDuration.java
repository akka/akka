/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util;

import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;
import scala.concurrent.duration.Duration;

public class JavaDuration extends JUnitSuite {

  @Test
  public void testCreation() {
    final Duration fivesec = Duration.create(5, "seconds");
    final Duration threemillis = Duration.create("3 millis");
    final Duration diff = fivesec.minus(threemillis);
    assert diff.lt(fivesec);
    assert Duration.Zero().lteq(Duration.Inf());
    assert Duration.Inf().gt(Duration.Zero().neg());
  }
}
