/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.actor;

//#imports

import akka.actor.TypedActor;
import akka.dispatch.*;
import akka.actor.*;
import akka.japi.*;
import scala.concurrent.Await;
import scala.concurrent.util.Duration;
import java.util.concurrent.TimeUnit;

//#imports
import java.lang.Exception;

import org.junit.Test;
import static org.junit.Assert.*;
public class TypedActorDocTestBase {
    Object someReference = null;
    ActorSystem system = null;

    //#typed-actor-iface
    public static interface Squarer {
      //#typed-actor-iface-methods
      void squareDontCare(int i); //fire-forget

      Future<Integer> square(int i); //non-blocking send-request-reply

      Option<Integer> squareNowPlease(int i);//blocking send-request-reply

      int squareNow(int i); //blocking send-request-reply
      //#typed-actor-iface-methods
    }
    //#typed-actor-iface

    //#typed-actor-impl
    static class SquarerImpl implements Squarer {
      private String name;

      public SquarerImpl() {
          this.name = "default";
      }

      public SquarerImpl(String name) {
        this.name = name;
      }

      //#typed-actor-impl-methods

      public void squareDontCare(int i) {
          int sq = i * i; //Nobody cares :(
      }

      public Future<Integer> square(int i) {
          return Futures.successful(i * i, TypedActor.dispatcher());
      }

      public Option<Integer> squareNowPlease(int i) {
          return Option.some(i * i);
      }

      public int squareNow(int i) {
          return i * i;
      }
      //#typed-actor-impl-methods
    }
    //#typed-actor-impl

  @Test public void mustGetTheTypedActorExtension() {

    try {
      //#typed-actor-extension-tools

      //Returns the Typed Actor Extension
      TypedActorExtension extension =
              TypedActor.get(system); //system is an instance of ActorSystem

      //Returns whether the reference is a Typed Actor Proxy or not
      TypedActor.get(system).isTypedActor(someReference);

      //Returns the backing Akka Actor behind an external Typed Actor Proxy
      TypedActor.get(system).getActorRefFor(someReference);

      //Returns the current ActorContext,
      // method only valid within methods of a TypedActor implementation
      ActorContext context = TypedActor.context();

      //Returns the external proxy of the current Typed Actor,
      // method only valid within methods of a TypedActor implementation
      Squarer sq = TypedActor.<Squarer>self();

      //Returns a contextual instance of the Typed Actor Extension
      //this means that if you create other Typed Actors with this,
      //they will become children to the current Typed Actor.
      TypedActor.get(TypedActor.context());

      //#typed-actor-extension-tools
    } catch (Exception e) {
      //dun care
    }
  }
  @Test public void createATypedActor() {
    try {
    //#typed-actor-create1
    Squarer mySquarer =
      TypedActor.get(system).typedActorOf(new TypedProps<SquarerImpl>(Squarer.class, SquarerImpl.class));
    //#typed-actor-create1
    //#typed-actor-create2
    Squarer otherSquarer =
      TypedActor.get(system).typedActorOf(new TypedProps<SquarerImpl>(Squarer.class,
        new Creator<SquarerImpl>() {
          public SquarerImpl create() { return new SquarerImpl("foo"); }
        }),
        "name");
    //#typed-actor-create2

    //#typed-actor-calls
    //#typed-actor-call-oneway
    mySquarer.squareDontCare(10);
    //#typed-actor-call-oneway

    //#typed-actor-call-future
    Future<Integer> fSquare = mySquarer.square(10); //A Future[Int]
    //#typed-actor-call-future

    //#typed-actor-call-option
    Option<Integer> oSquare = mySquarer.squareNowPlease(10); //Option[Int]
    //#typed-actor-call-option

    //#typed-actor-call-strict
    int iSquare = mySquarer.squareNow(10); //Int
    //#typed-actor-call-strict
    //#typed-actor-calls

    assertEquals(100, Await.result(fSquare, Duration.create(3, TimeUnit.SECONDS)).intValue());

    assertEquals(100, oSquare.get().intValue());

    assertEquals(100, iSquare);

    //#typed-actor-stop
    TypedActor.get(system).stop(mySquarer);
    //#typed-actor-stop

    //#typed-actor-poisonpill
    TypedActor.get(system).poisonPill(otherSquarer);
    //#typed-actor-poisonpill
    } catch(Exception e) {
//Ignore
    }
  }

    @Test public void createHierarchies() {
        try {
            //#typed-actor-hierarchy
            Squarer childSquarer =
                    TypedActor.get(TypedActor.context()).
                               typedActorOf(
                                       new TypedProps<SquarerImpl>(Squarer.class, SquarerImpl.class)
                               );
            //Use "childSquarer" as a Squarer
            //#typed-actor-hierarchy
        } catch (Exception e) {
            //dun care
        }
    }

 @Test public void proxyAnyActorRef() {
    try {
    //#typed-actor-remote
    Squarer typedActor =
      TypedActor.get(system).
        typedActorOf(
          new TypedProps<Squarer>(Squarer.class),
          system.actorFor("akka://SomeSystem@somehost:2552/user/some/foobar")
        );
    //Use "typedActor" as a FooBar
    //#typed-actor-remote
    } catch (Exception e) {
      //dun care
    }
  }
}
