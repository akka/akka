/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.channels

import akka.testkit.AkkaSpec
import akka.channels._
import akka.actor.Actor
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.concurrent.Future
import scala.concurrent.duration._
import akka.util.Timeout
import akka.testkit.TestProbe

object ChannelDocSpec {

  //#motivation0
  trait Request
  case class Command(msg: String) extends Request

  trait Reply
  case object CommandSuccess extends Reply
  case class CommandFailure(msg: String) extends Reply
  //#motivation0

  //#child
  case class Stats(b: Request)
  case object GetChild
  case class ChildRef(child: ChannelRef[(Request, Reply) :+: TNil])

  class Child extends Actor
    with Channels[(Stats, Nothing) :+: TNil, (Request, Reply) :+: TNil] {

    channel[Request] { (x, snd) =>
      parentChannel <-!- Stats(x)
      snd <-!- CommandSuccess
    }
  }

  class Parent extends Actor
    with Channels[TNil, (Stats, Nothing) :+: (GetChild.type, ChildRef) :+: TNil] {

    val child = createChild(new Child)

    channel[GetChild.type] { (_, snd) => ChildRef(child) -!-> snd }

    channel[Stats] { (x, _) =>
      // collect some stats
    }
  }

  //#child
}

class ChannelDocSpec extends AkkaSpec {

  import ChannelDocSpec._

  trait Msg

  class MsgA extends Msg
  class MsgB extends Msg
  class MsgC extends Msg
  class MsgD extends Msg

  "demonstrate why Typed Channels" in {
    def someActor = testActor
    //#motivation1
    val requestProcessor = someActor
    requestProcessor ! Command
    //#motivation1
    expectMsg(Command)

    /*
    //#motivation2
    val requestProcessor = new ChannelRef[(Request, Reply) :+: TNil](someActor)
    requestProcessor <-!- Command // this does not compile
    //#motivation2
     */

    type Example = //
    //#motivation-types
    (MsgA, MsgB) :+: (MsgC, MsgD) :+: TNil
    //#motivation-types
  }

  // only compile test
  "demonstrate channels creation" ignore {
    //#declaring-channels
    class AC extends Actor with Channels[TNil, (Request, Reply) :+: TNil] {
      channel[Request] { (req, snd) =>
        req match {
          case Command("ping") => snd <-!- CommandSuccess
          case _               =>
        }
      }
    }
    //#declaring-channels

    //#declaring-subchannels
    class ACSub extends Actor with Channels[TNil, (Request, Reply) :+: TNil] {
      channel[Command] { (cmd, snd) => snd <-!- CommandSuccess }
      channel[Request] { (req, snd) =>
        if (ThreadLocalRandom.current.nextBoolean) snd <-!- CommandSuccess
        else snd <-!- CommandFailure("no luck")
      }
    }
    //#declaring-subchannels
  }

  // only compile test
  "demonstrating message sending" ignore {
    import scala.concurrent.ExecutionContext.Implicits.global
    //#sending
    implicit val dummySender: ChannelRef[(Any, Nothing) :+: TNil] = ???
    implicit val timeout: Timeout = ??? // for the ask operations

    val channelA: ChannelRef[(MsgA, MsgB) :+: TNil] = ???
    val channelA2: ChannelRef[(MsgA, MsgB) :+: (MsgA, MsgC) :+: TNil] = ???
    val channelB: ChannelRef[(MsgB, MsgC) :+: TNil] = ???
    val channelC: ChannelRef[(MsgC, MsgD) :+: TNil] = ???

    val a = new MsgA
    val fA = Future { new MsgA }

    channelA <-!- a // send a to channelA
    a -!-> channelA // same thing as above

    channelA <-!- fA // eventually send the future’s value to channelA
    fA -!-> channelA // same thing as above

    val fB: Future[MsgB] = channelA <-?- a // ask the actor
    a -?-> channelA // same thing as above

    // ask the actor with multiple reply types
    // return type given in full for illustration
    val fM: Future[WrappedMessage[ //
    (MsgB, Nothing) :+: (MsgC, Nothing) :+: TNil, Msg]] = channelA2 <-?- a
    val fMunwrapped: Future[Msg] = fM.lub

    channelA <-?- fA // eventually ask the actor, return the future
    fA -?-> channelA // same thing as above

    // chaining works as well
    a -?-> channelA -?-> channelB -!-> channelC
    //#sending
  }

  "demonstrate message forwarding" in {
    //#forwarding
    import scala.reflect.runtime.universe.TypeTag

    class Latch[T1: TypeTag, T2: TypeTag](target: ChannelRef[(T1, T2) :+: TNil])
      extends Actor with Channels[TNil, (Request, Reply) :+: (T1, T2) :+: TNil] {

      implicit val timeout = Timeout(5.seconds)

      //#become
      channel[Request] {

        case (Command("close"), snd) =>
          channel[T1] { (t, s) => t -?-> target -!-> s }
          snd <-!- CommandSuccess

        case (Command("open"), snd) =>
          channel[T1] { (_, _) => }
          snd <-!- CommandSuccess
      }

      //#become
      channel[T1] { (t, snd) => t -?-> target -!-> snd }
    }
    //#forwarding

    val probe = TestProbe()
    val _target = new ChannelRef[(String, Int) :+: TNil](probe.ref)
    val _self = new ChannelRef[(Any, Nothing) :+: TNil](testActor)
    //#usage
    implicit val selfChannel: ChannelRef[(Any, Nothing) :+: TNil] = _self
    val target: ChannelRef[(String, Int) :+: TNil] = _target // some actor

    // type given just for demonstration purposes
    val latch: ChannelRef[(Request, Reply) :+: (String, Int) :+: TNil] =
      ChannelExt(system).actorOf(new Latch(target), "latch")

    "hello" -!-> latch
    //#processing
    probe.expectMsg("hello")
    probe.reply(5)
    //#processing
    expectMsg(5) // this is a TestKit-based example

    Command("open") -!-> latch
    expectMsg(CommandSuccess)

    "world" -!-> latch
    //#processing
    probe.expectNoMsg(500.millis)
    //#processing
    expectNoMsg(500.millis)
    //#usage
  }

  "demonstrate child creation" in {
    implicit val selfChannel = new ChannelRef[(Any, Nothing) :+: TNil](testActor)
    //#child
    // 
    // then it is used somewhat like this:
    // 

    val parent = ChannelExt(system).actorOf(new Parent, "parent")
    parent <-!- GetChild
    val child = expectMsgType[ChildRef].child // this assumes TestKit context

    child <-!- Command("hey there")
    expectMsg(CommandSuccess)
    //#child
  }

}