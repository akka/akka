/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor;

import java.util.concurrent.TimeoutException;
import java.time.Duration;
import org.junit.ClassRule;
import org.junit.Test;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.AkkaSpec;
import org.scalatestplus.junit.JUnitSuite;

public class InboxJavaAPITest extends JUnitSuite {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
      new AkkaJUnitActorSystemResource("InboxJavaAPITest", AkkaSpec.testConf());

  private final ActorSystem system = actorSystemResource.getSystem();

  @Test(expected = TimeoutException.class)
  public void mustBeAbleToThrowTimeoutException() throws TimeoutException {
    Inbox inbox = Inbox.create(system);
    inbox.receive(Duration.ofMillis(10));
  }
}
