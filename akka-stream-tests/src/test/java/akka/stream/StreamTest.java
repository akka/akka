/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.stream;

import akka.actor.ActorSystem;
import akka.stream.javadsl.AkkaJUnitActorSystemResource;

public abstract class StreamTest {
    final protected ActorSystem system;
    final protected ActorFlowMaterializer materializer;

    protected StreamTest(AkkaJUnitActorSystemResource actorSystemResource) {
        system = actorSystemResource.getSystem();
        ActorFlowMaterializerSettings settings = ActorFlowMaterializerSettings.create(system);
        materializer = ActorFlowMaterializer.create(settings, system);
    }
}
