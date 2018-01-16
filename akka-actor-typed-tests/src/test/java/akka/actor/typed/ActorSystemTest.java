package akka.actor.typed;

import org.junit.Assert;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.SECONDS;

public class ActorSystemTest extends JUnitSuite {

  @Test
  public void testGetWhenTerminated() throws Exception {
    final ActorSystem system = ActorSystem.create(Behavior.empty(), "GetWhenTerminatedSystem");
    system.terminate();
    final CompletionStage<Terminated> cs = system.getWhenTerminated();
    cs.toCompletableFuture().get(2, SECONDS);
  }

  @Test
  public void testGetWhenTerminatedWithoutTermination() throws Exception {
    final ActorSystem system = ActorSystem.create(Behavior.empty(), "GetWhenTerminatedWithoutTermination");
    final CompletionStage<Terminated> cs = system.getWhenTerminated();
    try {
      cs.toCompletableFuture().get(2, SECONDS);
      Assert.fail("System wasn't terminated, but getWhenTerminated() unexpectedly completed");
    } catch (TimeoutException e) {
      // we expect this to be thrown, because system wasn't terminated
    } finally {
      system.terminate();
    }
  }
}
