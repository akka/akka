/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor;

import static org.junit.Assert.*;

import akka.testkit.TestActors;
import org.junit.Test;

import akka.japi.Creator;

public class ActorCreationTest {

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

  static interface I<T> extends Creator<UntypedActor> {
  }

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
    public H(String a) {
    }
  }

  static class P implements Creator<UntypedActor> {
    final String value;

    public P(String value) {
      this.value = value;
    }

    @Override
    public UntypedActor create() throws Exception {
      return null;
    }
  }

  @Test
  public void testWrongAnonymousInPlaceCreator() {
    try {
      Props.create(new Creator<Actor>() {
        @Override
        public Actor create() throws Exception {
          return null;
        }
      });
      assert false;
    } catch (IllegalArgumentException e) {
      assertEquals("cannot use non-static local Creator to create actors; make it static (e.g. local to a static method) or top-level", e.getMessage());
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testWrongErasedStaticCreator() {
    try {
      Props.create(new G());
      assert false;
    } catch (IllegalArgumentException e) {
      assertEquals("erased Creator types are unsupported, use Props.create(actorClass, creator) instead", e.getMessage());
    }
    Props.create(UntypedActor.class, new G());
  }

  @Test
  public void testRightStaticCreator() {
    final Props p = Props.create(new C());
    assertEquals(UntypedActor.class, p.actorClass());
  }

  @Test
  public void testWrongAnonymousClassStaticCreator() {
    try {
      Props.create(new C() {}); // has implicit reference to outer class
      fail("Should have detected this is not a real static class, and thrown");
    } catch (IllegalArgumentException e) {
      assertEquals("cannot use non-static local Creator to create actors; make it static (e.g. local to a static method) or top-level", e.getMessage());
    }
  }

  @Test
  public void testRightTopLevelNonStaticCreator() {
    final Creator<UntypedActor> nonStatic = new NonStaticCreator();
    final Props p = Props.create(nonStatic);
    assertEquals(UntypedActor.class, p.actorClass());
  }

  @Test
  public void testRightStaticParametricCreator() {
    final Props p = Props.create(new D<UntypedActor>());
    assertEquals(Actor.class, p.actorClass());
  }

  @Test
  public void testRightStaticBoundedCreator() {
    final Props p = Props.create(new E<UntypedActor>());
    assertEquals(UntypedActor.class, p.actorClass());
  }

  @Test
  public void testRightStaticSuperinterface() {
    final Props p = Props.create(new F());
    assertEquals(UntypedActor.class, p.actorClass());
  }

  @Test
  public void testWrongAbstractActorClass() {
    try {
      Props.create(H.class, "a");
      assert false;
    } catch (IllegalArgumentException e) {
      assertEquals(String.format("Actor class [%s] must not be abstract", H.class.getName()), e.getMessage());
    }
  }

  private static Creator<UntypedActor> createAnonymousCreatorInStaticMethod() {
    return new Creator<UntypedActor>() {
      @Override
      public UntypedActor create() throws Exception {
        return null;
      }
    };
  }

  @Test
  public void testAnonymousClassCreatedInStaticMethodCreator() {
    final Creator<UntypedActor> anonymousCreatorFromStaticMethod = createAnonymousCreatorInStaticMethod();
    Props.create(anonymousCreatorFromStaticMethod);
  }

  @Test
  public void testAnonymousClassCreatorWithArguments() {
    final Creator<UntypedActor> anonymousCreatorFromStaticMethod = new P("hello");
    Props.create(anonymousCreatorFromStaticMethod);
  }

}
