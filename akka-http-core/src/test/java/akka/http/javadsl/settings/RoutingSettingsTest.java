/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.settings;

import akka.actor.ActorSystem;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

public class RoutingSettingsTest extends JUnitSuite {

    @Test
    public void testCreateWithActorSystem() {
        String testConfig =
            "akka.http.routing {\n" +
            "  verbose-error-messages = off\n" +
            "  file-get-conditional = on\n" +
            "  render-vanity-footer = yes\n" +
            "  range-coalescing-threshold = 80\n" +
            "  range-count-limit = 16\n" +
            "  decode-max-bytes-per-chunk = 1m\n" +
            "  file-io-dispatcher = \"test-only\"\n" +
            "}";
        Config config = ConfigFactory.parseString(testConfig);
        ActorSystem sys = ActorSystem.create("test", config);
        RoutingSettings settings = RoutingSettings.create(sys);
    }
}
