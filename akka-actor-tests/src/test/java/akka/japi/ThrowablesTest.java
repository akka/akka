/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.japi;

import org.junit.Assert;
import org.junit.Test;
import scala.util.control.ControlThrowable;

public class ThrowablesTest {
  @Test
  public void testIsNonFatal() {
    Assert.assertTrue(Throwables.isNonFatal(new IllegalArgumentException("isNonFatal")));
  }

  @Test
  public void testIsFatal() {
    Assert.assertTrue(Throwables.isFatal(new StackOverflowError("fatal")));
    Assert.assertTrue(Throwables.isFatal(new ThreadDeath()));
    Assert.assertTrue(Throwables.isFatal(new InterruptedException("fatal")));
    Assert.assertTrue(Throwables.isFatal(new LinkageError("fatal")));
  }
}
