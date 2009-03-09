/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package com.scalablesolutions.akka.supervisor

import org.specs.runner.JUnit4
import org.specs.Specification

import scala.actors._
import scala.actors.Actor._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class GenericServerTest extends JUnit4(genericServerSpec) // for JUnit4 and Maven
object genericServerSpec extends Specification {

  "server should respond to a regular message" in {
    val server = new TestGenericServerActor
    server.start
    server !? Ping match {
      case reply: String =>
        assert("got a ping" === server.log)
        assert("pong" === reply)
      case _ => fail()
    }
  }
}

class TestGenericServerActor extends GenericServer  {
  var log: String = ""

  override def body: PartialFunction[Any, Unit] = {
    case Ping =>
      log = "got a ping"
      reply("pong")
  }
}

