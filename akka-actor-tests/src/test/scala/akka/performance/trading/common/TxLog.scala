package akka.performance.trading.common

import akka.performance.trading.domain.Order

trait TxLog {

  def storeTx(order: Order)

  def close()

}
