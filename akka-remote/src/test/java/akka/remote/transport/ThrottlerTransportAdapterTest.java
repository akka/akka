/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.transport;

// compile only; verify java interop
public class ThrottlerTransportAdapterTest {

  public void compileThrottlerTransportAdapterDirections() {
    acceptDirection(ThrottlerTransportAdapter.bothDirection());
    acceptDirection(ThrottlerTransportAdapter.receiveDirection());
    acceptDirection(ThrottlerTransportAdapter.sendDirection());
  }

  public void compleThrottleMode() {
    acceptThrottleMode(ThrottlerTransportAdapter.unthrottledThrottleMode());
    acceptThrottleMode(ThrottlerTransportAdapter.blackholeThrottleMode());
    acceptThrottleMode(new ThrottlerTransportAdapter.TokenBucket(0, 0.0, 0, 0));
  }

  void acceptDirection(ThrottlerTransportAdapter.Direction dir) {}

  void acceptThrottleMode(ThrottlerTransportAdapter.ThrottleMode mode) {}
}
