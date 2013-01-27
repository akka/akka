package akka.japi;

import akka.actor.ExtendedActorSystem;
import akka.event.LoggingAdapter;
import akka.event.NoLogging;
import akka.serialization.JavaSerializer;
import org.junit.Test;
import java.util.concurrent.Callable;

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
}
