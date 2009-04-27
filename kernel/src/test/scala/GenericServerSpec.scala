/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import com.jteigen.scalatest.JUnit4Runner
import org.junit.runner.RunWith
import org.scalatest._

import scala.actors.Actor._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@RunWith(classOf[JUnit4Runner])
class GenericServerSpec extends Suite {

  def testSendRegularMessage = {
    val server = new MyGenericServerActor
    server.start
    server !? Ping match {
      case reply: String =>
        assert("got a ping" === server.log)
        assert("pong" === reply)
      case _ => fail()
    }
  }
}

class MyGenericServerActor extends GenericServer  {
  var log: String = ""

  override def body: PartialFunction[Any, Unit] = {
    case Ping =>
      log = "got a ping"
      reply("pong")
  }
}

