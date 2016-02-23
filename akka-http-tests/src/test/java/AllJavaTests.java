/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

import akka.http.javadsl.server.HandlerBindingTest;
import docs.http.javadsl.server.HandlerExampleDocTest;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({
        HandlerBindingTest.class,
        HandlerExampleDocTest.class
})
public class AllJavaTests {
}
