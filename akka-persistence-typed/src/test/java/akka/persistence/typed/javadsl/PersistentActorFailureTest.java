/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl;

import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import static akka.persistence.typed.javadsl.PersistentActorJavaDslTest.*;
import static akka.persistence.typed.scaladsl.PersistentBehaviorFailureSpec.conf;
import static java.util.Collections.singletonList;


public class PersistentActorFailureTest extends JUnitSuite {

  public static final Config config = conf().withFallback(ConfigFactory.load());

  @ClassRule
  public static final TestKitJunitResource testKit = new TestKitJunitResource(config);

 @Test
  public void persistEvents() throws Exception {
    ActorRef<Command> c = testKit.spawn(counter("c1", testKit));
    TestProbe<State> probe = testKit.createTestProbe();
    // fail
    c.tell(Increment.instance);
    Thread.sleep(50);
    // fail
    c.tell(Increment.instance);
    Thread.sleep(50);
    // work
    c.tell(Increment.instance);

    c.tell(new GetValue(probe.ref()));
    probe.expectMessage(new State(1, singletonList(0)));
  }
}
