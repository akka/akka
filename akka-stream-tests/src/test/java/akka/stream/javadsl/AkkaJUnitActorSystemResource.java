/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl;

import org.junit.rules.ExternalResource;

import akka.actor.ActorSystem;
import akka.stream.testkit.AkkaSpec;
import akka.testkit.JavaTestKit;

import com.typesafe.config.Config;

// FIXME remove this copy, and use akka.testkit.AkkaJUnitActorSystemResource when
//       akka-stream-experimental becomes a normal build project

/**
 * This is a resource for creating an actor system before test start and shut it
 * down afterwards.
 * 
 * To use it on a class level add this to your test class:
 * 
 * @ClassRule public static AkkaJUnitActorSystemResource actorSystemResource =
 *            new AkkaJUnitActorSystemResource(name, config);
 * 
 *            private final ActorSystem system =
 *            actorSystemResource.getSystem();
 * 
 * 
 *            To use it on a per test level add this to your test class:
 * 
 * @Rule public AkkaJUnitActorSystemResource actorSystemResource = new
 *       AkkaJUnitActorSystemResource(name, config);
 * 
 *       private final ActorSystem system = actorSystemResource.getSystem();
 */

public class AkkaJUnitActorSystemResource extends ExternalResource {
  private ActorSystem system = null;
  private final String name;
  private final Config config;

  private ActorSystem createSystem(String name, Config config) {
    try {
      if (config == null)
        return ActorSystem.create(name);
      else
        return ActorSystem.create(name, config);
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  public AkkaJUnitActorSystemResource(String name, Config config) {
    this.name = name;
    this.config = config;
    system = createSystem(name, config);
  }

  public AkkaJUnitActorSystemResource(String name) {
    this(name, AkkaSpec.testConf());
  }

  @Override
  protected void before() throws Throwable {
    // Sometimes the ExternalResource seems to be reused, and
    // we don't run the constructor again, so if that's the case
    // then create the system here
    if (system == null) {
      system = createSystem(name, config);
    }
  }

  @Override
  protected void after() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  public ActorSystem getSystem() {
    return system;
  }
}
