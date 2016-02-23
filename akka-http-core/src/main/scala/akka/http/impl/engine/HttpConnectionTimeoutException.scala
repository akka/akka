/*
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine

import scala.util.control.NoStackTrace

class HttpConnectionTimeoutException(msg: String) extends RuntimeException(msg) with NoStackTrace
