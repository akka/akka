/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream;

import akka.actor.ActorSystem;
import akka.testkit.AkkaJUnitActorSystemResource;
import org.scalatest.junit.JUnitSuite;

public abstract class StreamTest extends JUnitSuite {
  protected final ActorSystem system;

  protected StreamTest(AkkaJUnitActorSystemResource actorSystemResource) {
    system = actorSystemResource.getSystem();
  }
}
