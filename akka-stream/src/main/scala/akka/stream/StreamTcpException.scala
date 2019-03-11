/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import scala.util.control.NoStackTrace

class StreamTcpException(msg: String) extends RuntimeException(msg) with NoStackTrace

class BindFailedException extends StreamTcpException("bind failed")

@deprecated("BindFailedException object will never be thrown. Match on the class instead.")
case object BindFailedException extends BindFailedException

class ConnectionException(msg: String) extends StreamTcpException(msg)
