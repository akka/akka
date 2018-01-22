package akka.actor.typed;

import scala.concurrent.ExecutionContext;

import java.util.concurrent.Executor;

public class DispatcherSelectorTest {
  // Compile time only test to verify
  // dispatcher factories are accessible from Java

  private DispatcherSelector def = DispatcherSelector.defaultDispatcher();
  private DispatcherSelector conf = DispatcherSelector.fromConfig("somepath");
}
