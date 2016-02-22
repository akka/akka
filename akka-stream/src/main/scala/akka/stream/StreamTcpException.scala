/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream

import scala.util.control.NoStackTrace

class StreamTcpException(msg: String) extends RuntimeException(msg) with NoStackTrace

abstract class BindFailedException extends StreamTcpException("bind failed")

case object BindFailedException extends BindFailedException

class ConnectionException(msg: String) extends StreamTcpException(msg)

