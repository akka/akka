/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

import akka.http.javadsl.server.HandlerBindingTest;
import docs.http.javadsl.server.HandlerExampleSpec;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({
        HandlerBindingTest.class,
        HandlerExampleSpec.class
})
public class AllJavaTests {
}
