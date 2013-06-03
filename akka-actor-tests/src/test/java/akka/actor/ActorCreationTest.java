/**
 *   Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor;

import static org.junit.Assert.*;
import org.junit.Test;

import akka.japi.Creator;

public class ActorCreationTest {

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
      assertEquals("cannot use non-static local Creator to create actors; make it static or top-level", e.getMessage());
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
  
  @Test
  public void testRightCreator() {
    final Props p = Props.create(new C());
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
  
}
