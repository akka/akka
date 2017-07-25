/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server.testkit;

import akka.http.javadsl.testkit.JUnitRouteTest;
import org.junit.Test;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.TimeUnit;

public class WithTimeoutTest extends JUnitRouteTest {
    //#timeout-setting
    @Override
    public FiniteDuration awaitDuration() {
        return FiniteDuration.create(5, TimeUnit.SECONDS);
    }
    //#timeout-setting

    @Test
    public void dummy() {

    }
}
