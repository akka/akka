/*
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine

import scala.util.control.NoStackTrace

@deprecated("Not used. Will be replaced by `akka.stream.impl.io.TcpIdleTimeoutException` in Akka itself", since = "10.0.1")
class HttpConnectionTimeoutException(msg: String) extends RuntimeException(msg) with NoStackTrace
