package akka.performance.trading.system

import akka.performance.trading.domain.Order

case class Rsp(order: Order, status: Boolean)
