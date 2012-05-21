package akka.japi;

import org.junit.Test;

import static org.junit.Assert.*;

public class JavaAPITestBase {

  @Test
  public void shouldCreateSomeString() {
    Option<String> o = Option.some("abc");
    assertFalse(o.isEmpty());
    assertTrue(o.isDefined());
    assertEquals("abc", o.get());
  }

  @Test
  public void shouldCreateNone() {
    Option<String> o1 = Option.none();
    assertTrue(o1.isEmpty());
    assertFalse(o1.isDefined());

    Option<Float> o2 = Option.none();
    assertTrue(o2.isEmpty());
    assertFalse(o2.isDefined());
  }

  @Test
  public void shouldEnterForLoop() {
    for (@SuppressWarnings("unused")
    String s : Option.some("abc")) {
      return;
    }
    fail("for-loop not entered");
  }

  @Test
  public void shouldNotEnterForLoop() {
    for (@SuppressWarnings("unused")
    Object o : Option.none()) {
      fail("for-loop entered");
    }
  }

  @Test
  public void shouldBeSingleton() {
    assertSame(Option.none(), Option.none());
  }

  @Test
  public void partialProcedureDoesNotDoubleWrap() {
    PartialProcedure<Integer> p = new PartialProcedure<Integer>() {
      @Override
      public void apply(Integer i) {
      }
      @Override
      public boolean isDefinedAt(Integer i) {
        return i == 10;
      }
    };
    scala.PartialFunction<Integer, scala.runtime.BoxedUnit> s = p.asScala();
    assertEquals(false, p.isDefinedAt(9));
    assertEquals(true, p.isDefinedAt(10));
    assertEquals(false, s.isDefinedAt(9));
    assertEquals(true, s.isDefinedAt(10));
    scala.PartialFunction<Integer, scala.runtime.BoxedUnit> s2 = PartialProcedure.fromScalaPartialFunction(s).asScala();
    assertTrue("original scala partial function retrieved", s == s2);
  }
}
