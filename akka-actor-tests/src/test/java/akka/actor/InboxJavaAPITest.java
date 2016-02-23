/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.actor;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.ClassRule;
import org.junit.Test;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.AkkaSpec;
import scala.concurrent.duration.FiniteDuration;

public class InboxJavaAPITest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("InboxJavaAPITest",
      AkkaSpec.testConf());

  private final ActorSystem system = actorSystemResource.getSystem();

  @Test(expected = TimeoutException.class)
  public void mustBeAbleToThrowTimeoutException() throws TimeoutException {
    Inbox inbox = Inbox.create(system);
    inbox.receive(new FiniteDuration(10, TimeUnit.MILLISECONDS));
  }

}
