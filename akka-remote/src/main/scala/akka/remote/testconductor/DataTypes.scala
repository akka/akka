/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.testconductor

sealed trait ClientOp
sealed trait ServerOp

case class EnterBarrier(name: String) extends ClientOp with ServerOp
case class Throttle(node: String, target: String, direction: Direction, rateMBit: Float) extends ServerOp
case class Disconnect(node: String, target: String, abort: Boolean) extends ServerOp
case class Terminate(node: String, exitValueOrKill: Int) extends ServerOp
case class Remove(node: String) extends ServerOp
