/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.testkit;

import static org.junit.Assert.*;
import akka.actor.*;
import akka.japi.Creator;
import akka.japi.Function;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.JavaTestKit;
import akka.testkit.TestActorRef;
import akka.testkit.TestProbe;

import com.typesafe.config.ConfigFactory;

import org.junit.ClassRule;
import org.junit.Test;

public class ParentChildTest {
  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("TestKitDocTest",
                                     ConfigFactory.parseString("akka.loggers = [akka.testkit.TestEventListener]"));


  private final ActorSystem system = actorSystemResource.getSystem();

  //#test-example
  static class Parent extends UntypedActor {
    final ActorRef child = context().actorOf(Props.create(Child.class), "child");
    boolean ponged = false;

    @Override public void onReceive(Object message) throws Exception {
      if ("pingit".equals(message)) {
        child.tell("ping", self());
      } else if ("pong".equals(message)) {
        ponged = true;
      } else {
        unhandled(message);
      }
    }
  }

  static class Child extends UntypedActor {
    @Override public void onReceive(Object message) throws Exception {
      if ("ping".equals(message)) {
        context().parent().tell("pong", self());
      } else {
        unhandled(message);
      }
    }
  }
  //#test-example

  static
  //#test-dependentchild
  class DependentChild extends UntypedActor {
    private final ActorRef parent;

    public DependentChild(ActorRef parent) {
      this.parent = parent;
    }

    @Override public void onReceive(Object message) throws Exception {
      if ("ping".equals(message)) {
        parent.tell("pong", self());
      } else {
        unhandled(message);
      }
    }
  }
  //#test-dependentchild

  static
  //#test-dependentparent
  class DependentParent extends UntypedActor {
    final ActorRef child;
    boolean ponged = false;

    public DependentParent(Props childProps) {
      child = context().actorOf(childProps, "child");
    }

    @Override public void onReceive(Object message) throws Exception {
      if ("pingit".equals(message)) {
        child.tell("ping", self());
      } else if ("pong".equals(message)) {
        ponged = true;
      } else {
        unhandled(message);
      }
    }
  }
  //#test-dependentparent

  static
  //#test-dependentparent-generic
  class GenericDependentParent extends UntypedActor {
    final ActorRef child;
    boolean ponged = false;

    public GenericDependentParent(Function<ActorRefFactory, ActorRef> childMaker)
      throws Exception {
      child = childMaker.apply(context());
    }

    @Override public void onReceive(Object message) throws Exception {
      if ("pingit".equals(message)) {
        child.tell("ping", self());
      } else if ("pong".equals(message)) {
        ponged = true;
      } else {
        unhandled(message);
      }
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
      return new UntypedActor() {
        final ActorRef child = context().actorOf(Props.create(Child.class), "child");

        @Override public void onReceive(Object x) throws Exception {
          if (sender().equals(child)) {
            proxy.ref().forward(x, context());
          } else {
            child.forward(x, context());
          }
        }
      };
    }
  }
  //#test-fabricated-parent-creator

  @Test
  public void testProbeParentTest() throws Exception {
  //#test-TestProbe-parent
    JavaTestKit parent = new JavaTestKit(system);
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
