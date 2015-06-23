/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.stream;

import akka.actor.ActorSystem;
import akka.stream.javadsl.AkkaJUnitActorSystemResource;

public abstract class StreamTest {
    final protected ActorSystem system;
    final protected ActorMaterializer materializer;

    protected StreamTest(AkkaJUnitActorSystemResource actorSystemResource) {
        system = actorSystemResource.getSystem();
        ActorMaterializerSettings settings = ActorMaterializerSettings.create(system);
        materializer = ActorMaterializer.create(settings, system);
    }
}
