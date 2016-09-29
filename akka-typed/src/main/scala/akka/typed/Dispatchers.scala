/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed

import scala.concurrent.ExecutionContextExecutor
import java.util.concurrent.Executors
import akka.event.LoggingAdapter

trait Dispatchers {
  def lookup(selector: DispatcherSelector): ExecutionContextExecutor
  def shutdown(): Unit
}
