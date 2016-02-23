/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream;

import org.scalatest.junit.JUnitSuite;

import akka.actor.ActorSystem;
import akka.stream.javadsl.AkkaJUnitActorSystemResource;

public abstract class StreamTest extends JUnitSuite {
    final protected ActorSystem system;
    final protected ActorMaterializer materializer;

    protected StreamTest(AkkaJUnitActorSystemResource actorSystemResource) {
        system = actorSystemResource.getSystem();
        ActorMaterializerSettings settings = ActorMaterializerSettings.create(system);
        materializer = ActorMaterializer.create(settings, system);
    }
}
