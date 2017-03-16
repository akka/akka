/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.testkit;

import static org.junit.Assert.*;
import akka.actor.*;
import akka.japi.Creator;
import akka.japi.Function;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.TestActorRef;
import akka.testkit.TestProbe;

import akka.testkit.javadsl.TestKit;
import com.typesafe.config.ConfigFactory;

import docs.testkit.MockedChild;
import jdocs.AbstractJavaTest;
import org.junit.ClassRule;
import org.junit.Test;

public class ParentChildTest extends AbstractJavaTest {
  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("TestKitDocTest",
                                     ConfigFactory.parseString("akka.loggers = [akka.testkit.TestEventListener]"));


  private final ActorSystem system = actorSystemResource.getSystem();

  //#test-example
  static class Parent extends AbstractActor {
    final ActorRef child = getContext().actorOf(Props.create(Child.class), "child");
    boolean ponged = false;

    @Override
    public Receive createReceive() {
      return receiveBuilder()
        .matchEquals("pingit", message -> child.tell("ping", getSelf()))
        .matchEquals("pong", message -> ponged = true)
        .build();
    }
  }

  static class Child extends AbstractActor {
    @Override
    public Receive createReceive() {
      return receiveBuilder()
        .matchEquals("ping", message -> {
          getContext().getParent().tell("pong", getSelf());
        })
        .build();
    }
  }
  //#test-example

  static
  //#test-dependentchild
  class DependentChild extends AbstractActor {
    private final ActorRef parent;

    public DependentChild(ActorRef parent) {
      this.parent = parent;
    }

    @Override
    public Receive createReceive() {
      return receiveBuilder()
        .matchEquals("ping", message -> parent.tell("pong", getSelf()))
        .build();
    }
  }
  //#test-dependentchild

  static
  //#test-dependentparent
  class DependentParent extends AbstractActor {
    final ActorRef child;
    boolean ponged = false;

    public DependentParent(Props childProps) {
      child = getContext().actorOf(childProps, "child");
    }

    @Override
    public Receive createReceive() {
      return receiveBuilder()
        .matchEquals("pingit", message -> child.tell("ping", getSelf()))
        .matchEquals("pong", message -> ponged = true)
        .build();
    }
  }
  //#test-dependentparent

  static
  //#test-dependentparent-generic
  class GenericDependentParent extends AbstractActor {
    final ActorRef child;
    boolean ponged = false;

    public GenericDependentParent(Function<ActorRefFactory, ActorRef> childMaker)
      throws Exception {
      child = childMaker.apply(getContext());
    }

    @Override
    public Receive createReceive() {
      return receiveBuilder()
        .matchEquals("pingit", message -> child.tell("ping", getSelf()))
        .matchEquals("pong", message -> ponged = true)
        .build();
    }
  }
  //#test-dependentparent-generic

  @Test
  public void testingWithoutParent() {
    TestProbe probe = new TestProbe(system);
    ActorRef child = system.actorOf(Props.create(DependentChild.class, probe.ref()));
    probe.send(child, "ping");
    probe.expectMsg("pong");
  }

  @Test
  public void testingWithCustomProps() {
    TestProbe probe = new TestProbe(system);
    Props childProps = Props.create(MockedChild.class);
    TestActorRef<DependentParent> parent = TestActorRef.create(system, Props.create(DependentParent.class, childProps));

    probe.send(parent, "pingit");

    // test some parent state change
    assertTrue(parent.underlyingActor().ponged == true || parent.underlyingActor().ponged == false);
  }


  @Test
  public void testingWithChildProbe() throws Exception {
    final TestProbe probe = new TestProbe(system);
    //#child-maker-test
    Function<ActorRefFactory, ActorRef> maker = new Function<ActorRefFactory, ActorRef>() {
      @Override public ActorRef apply(ActorRefFactory param) throws Exception {
        return probe.ref();
      }
    };
    ActorRef parent = system.actorOf(Props.create(GenericDependentParent.class, maker));
    //#child-maker-test
    probe.send(parent, "pingit");
    probe.expectMsg("ping");
  }

  public void exampleProdActorFactoryFunction() throws Exception {
    //#child-maker-prod
    Function<ActorRefFactory, ActorRef> maker = new Function<ActorRefFactory, ActorRef>() {
      @Override public ActorRef apply(ActorRefFactory f) throws Exception {
        return f.actorOf(Props.create(Child.class));
      }
    };
    ActorRef parent = system.actorOf(Props.create(GenericDependentParent.class, maker));
    //#child-maker-prod
  }

  static
  //#test-fabricated-parent-creator
  class FabricatedParentCreator implements Creator<Actor> {
    private final TestProbe proxy;

    public FabricatedParentCreator(TestProbe proxy) {
      this.proxy = proxy;
    }

    @Override public Actor create() throws Exception {
      return new AbstractActor() {
        final ActorRef child = getContext().actorOf(Props.create(Child.class), "child");

        @Override
        public Receive createReceive() {
          return receiveBuilder()
            .matchAny(message -> {
              if (sender().equals(child)) {
                proxy.ref().forward(message, getContext());
              } else {
                child.forward(message, getContext());
              }
            })
            .build();

        }
      };
    }
  }
  //#test-fabricated-parent-creator

  @Test
  public void testProbeParentTest() throws Exception {
  //#test-TestProbe-parent
    TestKit parent = new TestKit(system);
    ActorRef child = parent.childActorOf(Props.create(Child.class));
    
    parent.send(child, "ping");
    parent.expectMsgEquals("pong");
  //#test-TestProbe-parent
  }
  
  @Test
  public void fabricatedParentTestsItsChildResponses() throws Exception {
    // didn't put final on these in order to make the parent fit in one line in the html docs
    //#test-fabricated-parent
    TestProbe proxy = new TestProbe(system);
    ActorRef parent = system.actorOf(Props.create(new FabricatedParentCreator(proxy)));

    proxy.send(parent, "ping");
    proxy.expectMsg("pong");
    //#test-fabricated-parent
  }

}
