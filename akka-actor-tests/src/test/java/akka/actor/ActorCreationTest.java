/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.actor;

import static org.junit.Assert.*;
import org.junit.Test;

import akka.japi.Creator;
import org.scalatest.junit.JUnitSuite;

public class ActorCreationTest extends JUnitSuite {

  @Test
  public void testWrongCreator() {
    try {
      Props.create(new Creator<Actor>() {
        @Override
        public Actor create() throws Exception {
          return null;
        }
      });
      assert false;
    } catch(IllegalArgumentException e) {
      assertEquals("cannot use non-static local Creator to create actors; make it static (e.g. local to a static method) or top-level", e.getMessage());
    }
  }
  
  static class C implements Creator<UntypedActor> {
    @Override
    public UntypedActor create() throws Exception {
      return null;
    }
  }
  
  static class D<T> implements Creator<T> {
    @Override
    public T create() {
      return null;
    }
  }
  
  static class E<T extends UntypedActor> implements Creator<T> {
    @Override
    public T create() {
      return null;
    }
  }
  
  static interface I<T> extends Creator<UntypedActor> {}
  static class F implements I<Object> {
    @Override
    public UntypedActor create() {
      return null;
    }
  }


  static class G implements Creator {
    public Object create() {
      return null;
    }
  }
  
  abstract class H extends UntypedActor {
    public H(String a) {}
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testErasedCreator() {
    try {
      Props.create(new G());
      assert false;
    } catch(IllegalArgumentException e) {
      assertEquals("erased Creator types are unsupported, use Props.create(actorClass, creator) instead", e.getMessage());
    }
    Props.create(UntypedActor.class, new G());
  }

  @Test
  public void testRightCreator() {
    final Props p = Props.create(new C());
    assertEquals(UntypedActor.class, p.actorClass());
  }

  @Test
  public void testTopLevelNonStaticCreator() {
    final Props p = Props.create(new NonStaticCreator());
    assertEquals(UntypedActor.class, p.actorClass());
  }

  @Test
  public void testParametricCreator() {
    final Props p = Props.create(new D<UntypedActor>());
    assertEquals(Actor.class, p.actorClass());
  }
  
  @Test
  public void testBoundedCreator() {
    final Props p = Props.create(new E<UntypedActor>());
    assertEquals(UntypedActor.class, p.actorClass());
  }
  
  @Test
  public void testSuperinterface() {
    final Props p = Props.create(new F());
    assertEquals(UntypedActor.class, p.actorClass());
  }
  
  @Test
  public void testRejectAbstractActorClass() {
    try {
      Props.create(H.class, "a");
      assert false;
    } catch (IllegalArgumentException e) {
      assertEquals(String.format("Actor class [%s] must not be abstract", H.class.getName()), e.getMessage());
    }
  }
  
}
