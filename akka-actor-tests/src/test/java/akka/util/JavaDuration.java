/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util;

import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;
import scala.concurrent.duration.Duration;

import static org.junit.Assert.assertTrue;

public class JavaDuration extends JUnitSuite {

  @Test
  public void testCreation() {
    final Duration fivesec = Duration.create(5, "seconds");
    final Duration threemillis = Duration.create("3 millis");
    final Duration diff = fivesec.minus(threemillis);
    assertTrue(diff.lt(fivesec));
    assertTrue(Duration.Zero().lteq(Duration.Inf()));
    assertTrue(Duration.Inf().gt(Duration.Zero().neg()));
  }
}
