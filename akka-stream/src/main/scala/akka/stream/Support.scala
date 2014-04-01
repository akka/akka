/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.util.control.NoStackTrace

case object Stop extends RuntimeException("Stop this flow") with NoStackTrace