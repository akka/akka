/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.japi;

import akka.actor.ExtendedActorSystem;
import akka.event.LoggingAdapter;
import akka.event.NoLogging;
import akka.serialization.JavaSerializer;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.util.Optional;
import java.util.concurrent.Callable;

import static org.junit.Assert.*;

public class JavaAPITestBase extends JUnitSuite {

  @Test
  public void shouldCreateSomeString() {
    Option<String> o = Option.some("abc");
    assertFalse(o.isEmpty());
    assertTrue(o.isDefined());
    assertEquals("abc", o.get());
    assertEquals("abc", o.getOrElse("other"));
  }

  @Test
  public void shouldCreateNone() {
    Option<String> o1 = Option.none();
    assertTrue(o1.isEmpty());
    assertFalse(o1.isDefined());
    assertEquals("other", o1.getOrElse("other"));

    Option<Float> o2 = Option.none();
    assertTrue(o2.isEmpty());
    assertFalse(o2.isDefined());
    assertEquals("other", o1.getOrElse("other"));
  }

  @Test
  public void shouldEnterForLoop() {
    for (@SuppressWarnings("unused")
    String s : Option.some("abc")) {
      return;
    }
    org.junit.Assert.fail("for-loop not entered");
  }

  @Test
  public void shouldNotEnterForLoop() {
    for (@SuppressWarnings("unused")
    Object o : Option.none()) {
      org.junit.Assert.fail("for-loop entered");
    }
  }

  @Test
  public void shouldBeSingleton() {
    assertSame(Option.none(), Option.none());
  }

  @Test
  public void mustBeAbleToGetNoLogging() {
      LoggingAdapter a = NoLogging.getInstance();
      assertNotNull(a);
  }
    
  @Test
  public void mustBeAbleToUseCurrentSystem() {
      assertNull(JavaSerializer.currentSystem().withValue(null, new Callable<ExtendedActorSystem>() {
          public ExtendedActorSystem call() {
              return JavaSerializer.currentSystem().value();
          }
      }));
  }

  @SuppressWarnings("unused") // compilation tests
  public void mustCompileUtilNullClassValueCases() {
    // this is the syntax that doesn't compile
    // final Flow<Optional<String>> f0 = Flow.of(Optional.class);
    //   incompatible types: inferred type does not conform to equality constraint(s)
    //     inferred: java.util.Optional<java.lang.String>
    //     equality constraints(s): java.util.Optional<java.lang.String>,java.util.Optional

    // first test the Java-only alternative
    @SuppressWarnings("unchecked")
    final Flow<Optional<String>> f1 = Flow.of((Class<Optional<String>>) (Class<?>) Optional.class);

    // now test our method, for a trivial non-generic type
    final Flow<String> f2 = Flow.of(Util.nullClassValue());

    // and now with a generic type
    final Flow<Optional<String>> f3 = Flow.of(Util.nullClassValue());

    // and test the syntax for specifying the type parameter
    Flow.of(Util.<Optional<String>>nullClassValue());
  }

  /**
   * A copy of akka.stream.javadsl.Flow used by
   * {@link JavaAPITestBase#mustCompileUtilNullClassValueCases()} to test the API usage.
   */
  private static final class Flow<T> {
    static <T> Flow<T> of(@SuppressWarnings("unused") final Class<T> clazz) { return new Flow<>(); }
  }

  @Test
  public void shouldReturnNullClassValue() {
    final Class<Optional<String>> c = Util.nullClassValue();
    assertNull(c);
  }

}
