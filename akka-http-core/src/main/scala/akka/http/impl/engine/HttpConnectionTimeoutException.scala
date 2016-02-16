/*
 * Copyright (C) 2015-2016 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine

import scala.util.control.NoStackTrace

class HttpConnectionTimeoutException(msg: String) extends RuntimeException(msg) with NoStackTrace
