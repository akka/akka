/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.sourceorflow

import akka.stream.scaladsl.Source

import scala.util.control.NoStackTrace

object MapError extends App {

  //#map-error
  Source(-1 to 1).map(1 / _).mapError {
    case _: ArithmeticException =>
      throw new UnsupportedOperationException("Divide by Zero Operation is not supported") with NoStackTrace
  }
  //#map-error

}
