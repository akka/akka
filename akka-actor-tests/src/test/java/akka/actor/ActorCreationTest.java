/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.actor;

import static org.junit.Assert.*;
import static java.util.stream.Collectors.toCollection;

import java.util.ArrayList;
import java.util.stream.IntStream;

import akka.testkit.TestActors;
import org.junit.Test;

import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;

import org.scalatest.junit.JUnitSuite;

public class ActorCreationTest extends JUnitSuite {

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

  public static class TestActor extends AbstractActor {
    public static Props propsUsingLamda(Integer magicNumber) {
      // You need to specify the actual type of the returned actor
      // since Java 8 lambdas have some runtime type information erased
      return Props.create(TestActor.class, () -> new TestActor(magicNumber));
    }

    public static Props propsUsingLamdaWithoutClass(Integer magicNumber) {
      return Props.create(() -> new TestActor(magicNumber));
    }

    @SuppressWarnings("unused")
    private final Integer magicNumber;

    TestActor(Integer magicNumber) {
      this.magicNumber = magicNumber;
    }
  }

  public static class UntypedTestActor extends UntypedActor {

    public static Props propsUsingCreator(final int magicNumber) {
      // You need to specify the actual type of the returned actor
      // since runtime type information erased
      return Props.create(UntypedTestActor.class, new Creator<UntypedTestActor>() {
        private static final long serialVersionUID = 1L;

        @Override
        public UntypedTestActor create() throws Exception {
          return new UntypedTestActor(magicNumber);
        }
      });
    }

    public static Props propsUsingCreatorWithoutClass(final int magicNumber) {
      return Props.create(new Creator<UntypedTestActor>() {
        private static final long serialVersionUID = 1L;

        @Override
        public UntypedTestActor create() throws Exception {
          return new UntypedTestActor(magicNumber);
        }
      });
    }

    private static final Creator<UntypedTestActor> staticCreator = new Creator<UntypedTestActor>() {
      private static final long serialVersionUID = 1L;

      @Override
      public UntypedTestActor create() throws Exception {
        return new UntypedTestActor(12);
      }
    };

    public static Props propsUsingStaticCreator(final int magicNumber) {



      return Props.create(staticCreator);
    }

    final int magicNumber;

    UntypedTestActor(int magicNumber) {
      this.magicNumber = magicNumber;
    }

    @Override
    public void onReceive(Object msg) {
    }
  }

  public static class Issue20537Reproducer extends UntypedActor {

    static final class ReproducerCreator implements Creator<Issue20537Reproducer> {

      final boolean create;

      private ReproducerCreator(boolean create) {
        this.create = create;
      }

      @Override
      public Issue20537Reproducer create() throws Exception {
        return new Issue20537Reproducer(create);
      }
    }

    public Issue20537Reproducer(boolean create) {
    }

    @Override
    public void onReceive(Object message) throws Exception {
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
  public void testClassCreatorWithArguments() {
    final Creator<UntypedActor> anonymousCreatorFromStaticMethod = new P("hello");
    Props.create(anonymousCreatorFromStaticMethod);
  }

  @Test
  public void testAnonymousClassCreatorWithArguments() {
    try {
      final Creator<UntypedActor> anonymousCreatorFromStaticMethod = new P("hello") {
        // captures enclosing class
      };
      Props.create(anonymousCreatorFromStaticMethod);
      fail("Should have detected this is not a real static class, and thrown");
    } catch (IllegalArgumentException e) {
      assertEquals("cannot use non-static local Creator to create actors; make it static (e.g. local to a static method) or top-level", e.getMessage());
    }
  }

  @Test
  public void testRightPropsUsingLambda() {
    final Props p = TestActor.propsUsingLamda(17);
    assertEquals(TestActor.class, p.actorClass());
  }

  @Test
  public void testWrongPropsUsingLambdaWithoutClass() {
    final Props p = TestActor.propsUsingLamda(17);
    assertEquals(TestActor.class, p.actorClass());
    try {
      TestActor.propsUsingLamdaWithoutClass(17);
      fail("Should have detected lambda erasure, and thrown");
    } catch (IllegalArgumentException e) {
      assertEquals("erased Creator types are unsupported, use Props.create(actorClass, creator) instead",
          e.getMessage());
    }
  }

  @Test
  public void testRightPropsUsingCreator() {
    final Props p = UntypedTestActor.propsUsingCreator(17);
    assertEquals(UntypedTestActor.class, p.actorClass());
  }

  @Test
  public void testPropsUsingCreatorWithoutClass() {
    final Props p = UntypedTestActor.propsUsingCreatorWithoutClass(17);
    assertEquals(UntypedTestActor.class, p.actorClass());
  }

  @Test
  public void testIssue20537Reproducer() {
    final Issue20537Reproducer.ReproducerCreator creator = new Issue20537Reproducer.ReproducerCreator(false);
    final Props p = Props.create(creator);
    assertEquals(Issue20537Reproducer.class, p.actorClass());

    ArrayList<Props> pList = IntStream.range(0, 4).mapToObj(i -> Props.create(creator))
        .collect(toCollection(ArrayList::new));
    for (Props each : pList) {
      assertEquals(Issue20537Reproducer.class, each.actorClass());
    }
  }


}
