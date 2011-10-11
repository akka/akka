package akka.performance.trading.response

import org.junit.Test
import akka.actor.ActorRef
import akka.performance.trading.common.AkkaPerformanceTest
import akka.performance.trading.domain.Order
import akka.performance.trading.common.Rsp
import akka.AkkaApplication

class RspPerformanceTest extends AkkaPerformanceTest(AkkaApplication()) {

  implicit def appl = app

  override def placeOrder(orderReceiver: ActorRef, order: Order): Rsp = {
    (orderReceiver ? order).get.asInstanceOf[Rsp]
  }

  // need this so that junit will detect this as a test case
  @Test
  def dummy {}

  override def compareResultWith = Some("OneWayPerformanceTest")

}

