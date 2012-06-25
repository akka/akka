/*
 * 
 */
package docs.testkit;

import static org.junit.Assert.*;

import org.junit.AfterClass;
import org.junit.Test;

import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.dispatch.Await;
import akka.dispatch.Future;
import akka.japi.JAPI;
import akka.japi.PurePartialFunction;
import akka.testkit.TestActorRef;
import akka.testkit.TestKit;
import akka.util.Duration;

public class TestKitDocTest {
  
  //#test-actor-ref
  static class MyActor extends UntypedActor {
    public void onReceive(Object o) throws Exception {
      if (o.equals("say42")) {
        getSender().tell(42, getSelf());
      } else if (o instanceof Exception) {
        throw (Exception) o;
      }
    }
    public boolean testMe() { return true; }
  }
  
  //#test-actor-ref
  
  private static ActorSystem system;
  
  public TestKitDocTest() {
    system = ActorSystem.create();
  }
  
  @AfterClass
  public static void cleanup() {
    system.shutdown();
  }

  //#test-actor-ref
  @Test
  public void demonstrateTestActorRef() {
    final Props props = new Props(MyActor.class);
    final TestActorRef<MyActor> ref = TestActorRef.apply(props, system);
    final MyActor actor = ref.underlyingActor();
    assertTrue(actor.testMe());
  }
  //#test-actor-ref
  
  //#test-behavior
  @Test
  public void demonstrateAsk() throws Exception {
    final Props props = new Props(MyActor.class);
    final TestActorRef<MyActor> ref = TestActorRef.apply(props, system);
    final Future<Object> future = akka.pattern.Patterns.ask(ref, "say42", 3000);
    assertTrue(future.isCompleted());
    assertEquals(42, Await.result(future, Duration.Zero()));
  }
  //#test-behavior
  
  //#test-expecting-exceptions
  @Test
  public void demonstrateExceptions() {
    final Props props = new Props(MyActor.class);
    final TestActorRef<MyActor> ref = TestActorRef.apply(props, system);
    try {
      ref.receive(new Exception("expected"));
      fail("expected an exception to be thrown");
    } catch (Exception e) {
      assertEquals("expected", e.getMessage());
    }
  }
  //#test-expecting-exceptions
  
  //#test-within
  @Test
  public void demonstrateWithin() {
    new TestKit(system) {{
      testActor().tell(42);
      new Within(Duration.parse("1 second")) {
        // do not put code outside this method, will run afterwards
        public void run() {
          assertEquals((Integer) 42, expectMsgClass(Integer.class));
        }
      };
    }};
  }
  //#test-within
  
  @Test
  public void demonstrateExpectMsgPF() {
    new TestKit(system) {{
      testActor().tell(42);
      //#test-expect-pf
      final String out = expectMsgPF(Duration.parse("1 second"), "fourty-two",
        new PurePartialFunction<Object, String>() {
          public String apply(Object in, boolean isCheck) {
            if (Integer.valueOf(42).equals(in)) {
              return "match";
            } else {
              throw noMatch();
            }
          }
        }
      );
      assertEquals("match", out);
      //#test-expect-pf
      testActor().tell("world");
      //#test-expect-anyof
      final String any = expectMsgAnyOf(remaining(), JAPI.seq("hello", "world"));
      //#test-expect-anyof
      assertEquals("world", any);
      testActor().tell("world");
      //#test-expect-anyclassof
      @SuppressWarnings("unchecked")
      final String anyClass = expectMsgAnyClassOf(remaining(), JAPI.<Class<? extends String>>seq(String.class));
      //#test-expect-anyclassof
      assertEquals("world", any);
    }};
  }
  
}
