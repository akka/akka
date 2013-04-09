/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

abstract class TestMessage

case object Ping extends TestMessage
case object Pong extends TestMessage
case object OneWay extends TestMessage
case object Die extends TestMessage
case object NotifySupervisorExit extends TestMessage
