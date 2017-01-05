/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.actor;

import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.AkkaSpec;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class ActorSelectionTest extends JUnitSuite {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("ActorSelectionTest",
    AkkaSpec.testConf());

  private final ActorSystem system = actorSystemResource.getSystem();

  @Test
  public void testResolveOneCS() throws Exception {
    ActorRef actorRef = system.actorOf(Props.create(JavaAPITestActor.class), "ref1");
    ActorSelection selection = system.actorSelection("user/ref1");
    FiniteDuration timeout = new FiniteDuration(10, TimeUnit.MILLISECONDS);

    CompletionStage<ActorRef> cs = selection.resolveOneCS(timeout);

    ActorRef resolvedRef = cs.toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals(actorRef, resolvedRef);
  }
}
