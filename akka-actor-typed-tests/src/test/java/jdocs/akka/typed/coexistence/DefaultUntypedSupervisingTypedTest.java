/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed.coexistence;

import akka.actor.ActorSystem;
import akka.actor.typed.ActorRef;
import akka.actor.typed.javadsl.Adapter;
import akka.testkit.javadsl.TestKit;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import static jdocs.akka.typed.coexistence.TypedWatchingUntypedTest.Typed;

public class DefaultUntypedSupervisingTypedTest extends JUnitSuite {

    @Test
    public void test() {
        //#spawn-untyped
        ActorSystem untypedActorSystem = ActorSystem.create();
        // Parent is the untyped user guardian which defaults to restarting failed children
        ActorRef<Typed.Command> typed = Adapter.spawn(untypedActorSystem, Typed.behavior(), "Typed");
        //#spawn-untyped
        typed.tell(new Typed.Pong());
        TestKit.shutdownActorSystem(untypedActorSystem);
    }
}
