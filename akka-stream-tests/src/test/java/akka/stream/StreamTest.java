/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream;

import akka.stream.testkit.javadsl.StreamTestKit;
import org.junit.After;
import org.junit.Before;
import org.scalatest.junit.JUnitSuite;

import akka.actor.ActorSystem;
import akka.testkit.AkkaJUnitActorSystemResource;

public abstract class StreamTest extends JUnitSuite {
  protected final ActorSystem system;
  private final ActorMaterializerSettings settings;

  protected ActorMaterializer materializer;

  protected StreamTest(AkkaJUnitActorSystemResource actorSystemResource) {
    system = actorSystemResource.getSystem();
    settings = ActorMaterializerSettings.create(system);
  }

  @Before
  public void setUp() {
    materializer = ActorMaterializer.create(settings, system);
  }

  @After
  public void tearDown() {
    StreamTestKit.assertAllStagesStopped(materializer);
    materializer.shutdown();
  }
}
