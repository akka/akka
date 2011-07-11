package akka.performance.trading.response

import org.junit.Test

import akka.actor.ActorRef
import akka.performance.trading.common.AkkaPerformanceTest
import akka.performance.trading.domain.Order
import akka.performance.trading.common.Rsp

class RspPerformanceTest extends AkkaPerformanceTest {

  override def placeOrder(orderReceiver: ActorRef, order: Order): Rsp = {
    (orderReceiver ? order).get.asInstanceOf[Rsp]
  }

  // need this so that junit will detect this as a test case
  @Test
  def dummy {}

  override def compareResultWith = Some("OneWayPerformanceTest")

}

