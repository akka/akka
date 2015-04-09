/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.stream;

import akka.actor.ActorSystem;
import akka.event.Logging;
import akka.stream.javadsl.AkkaJUnitActorSystemResource;
import akka.stream.OperationAttributes;

public abstract class StreamTest {
    final protected ActorSystem system;
    final protected ActorFlowMaterializer materializer;

    protected StreamTest(AkkaJUnitActorSystemResource actorSystemResource) {
        system = actorSystemResource.getSystem();
        ActorFlowMaterializerSettings settings = ActorFlowMaterializerSettings.create(system);
        materializer = ActorFlowMaterializer.create(settings, system);
    }
}
