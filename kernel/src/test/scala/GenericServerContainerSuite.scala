/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import scala.actors._
import scala.actors.Actor._

import com.jteigen.scalatest.JUnit4Runner
import org.junit.runner.RunWith
import org.scalatest._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@RunWith(classOf[JUnit4Runner])
class GenericServerContainerSpec extends Suite {

  var inner: GenericServerContainerActor = null
  var server: GenericServerContainer = null
  def createProxy(f: () => GenericServer) = { val server = new GenericServerContainer("server", f); server.setTimeout(100); server }

  def setup = {
    inner = new GenericServerContainerActor
    server = createProxy(() => inner)
    server.newServer
    server.start
  }

  def testInit = {
    setup
    server.init("testInit")
    Thread.sleep(100)
    expect("initializing: testInit") {
      inner.log
    }
  }

  def testTerminateWithReason = {
    setup
    server.terminate("testTerminateWithReason", 100)
    Thread.sleep(100)
    expect("terminating: testTerminateWithReason") {
      inner.log
    }
  }

  def test_bang_1 = {
    setup
    server ! OneWay
    Thread.sleep(100)
    expect("got a oneway") {
      inner.log
    }
  }

  def test_bang_2 = {
    setup
    server ! Ping
    Thread.sleep(100)
    expect("got a ping") {
      inner.log
    }
  }

  def test_bangbangbang = {
    setup
    expect("pong") {
      (server !!! Ping).getOrElse("nil")
    }
    expect("got a ping") {
      inner.log
    }
  }

  def test_bangquestion = {
    setup
    expect("pong") {
      val res: String = server !? Ping
      res
    }
    expect("got a ping") {
      inner.log
    }
  }

  def test_bangbangbang_Timeout1 = {
    setup
    expect("pong") {
      (server !!! Ping).getOrElse("nil")
    }
    expect("got a ping") {
      inner.log
    }
  }

  def test_bangbangbang_Timeout2 = {
    setup
    expect("error handler") {
      server !!! (OneWay, "error handler")
    }
    expect("got a oneway") {
      inner.log
    }
  }

  def test_bangbangbang_GetFutureTimeout1 = {
    setup
    val future = server !! Ping
    future.receiveWithin(100) match {
      case None => fail("timed out") // timed out
      case Some(reply) =>
        expect("got a ping") {
          inner.log
        }
        assert("pong" === reply)
    }
  }

  def test_bangbangbang_GetFutureTimeout2 = {
    setup
    val future = server !! OneWay
    future.receiveWithin(100) match {
      case None =>
        expect("got a oneway") {
          inner.log
        }
      case Some(reply) =>
        fail("expected a timeout, got Some(reply)")
    }
  }

  def testHotSwap = {
    setup
    // using base
    expect("pong") {
      (server !!! Ping).getOrElse("nil")
    }

    // hotswapping
    server.hotswap(Some({
      case Ping => reply("hotswapped pong")
    }))
    expect("hotswapped pong") {
      (server !!! Ping).getOrElse("nil")
    }
  }

  def testDoubleHotSwap = {
    setup
    // using base
    expect("pong") {
      (server !!! Ping).getOrElse("nil")
    }

    // hotswapping
    server.hotswap(Some({
      case Ping => reply("hotswapped pong")
    }))
    expect("hotswapped pong") {
      (server !!! Ping).getOrElse("nil")
    }

    // hotswapping again
    server.hotswap(Some({
      case Ping => reply("hotswapped pong again")
    }))
    expect("hotswapped pong again") {
      (server !!! Ping).getOrElse("nil")
    }
  }


  def testHotSwapReturnToBase = {
    setup
    // using base
    expect("pong") {
      (server !!! Ping).getOrElse("nil")
    }

    // hotswapping
    server.hotswap(Some({
      case Ping => reply("hotswapped pong")
    }))
    expect("hotswapped pong") {
      (server !!! Ping).getOrElse("nil")
    }

    // restoring original base
    server.hotswap(None)
    expect("pong") {
      (server !!! Ping).getOrElse("nil")
    }
  }
}


class GenericServerContainerActor extends GenericServer  {
  var log = ""

  override def body: PartialFunction[Any, Unit] = {
    case Ping =>
      log = "got a ping"
      reply("pong")

    case OneWay =>
      log = "got a oneway"
  }

  override def init(config: AnyRef) = log = "initializing: " + config
  override def shutdown(reason: AnyRef) = log = "terminating: " + reason
}


