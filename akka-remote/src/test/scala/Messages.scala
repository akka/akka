/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

case class User(val usernamePassword: Tuple2[String, String],
                val email: String,
                val age: Int) extends java.io.Serializable

case object RemotePing extends TestMessage
case object RemotePong extends TestMessage
case object RemoteOneWay extends TestMessage
case object RemoteDie extends TestMessage
case object RemoteNotifySupervisorExit extends TestMessage
