/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

import akka.testkit.AkkaSpec
import akka.util.unused
import com.github.ghik.silencer.silent

object PropsCreationSpec {

  final class A

  final class B

  class OneParamActor(@unused blackhole: A) extends Actor {
    override def receive = Actor.emptyBehavior
  }

  class TwoParamActor(@unused blackhole1: A, @unused blackhole2: B) extends Actor {
    override def receive = Actor.emptyBehavior
  }

  object OneParamActorCreator extends akka.japi.Creator[Actor] {
    override def create(): Actor = new OneParamActor(null)
  }

}

class PropsCreationSpec extends AkkaSpec("akka.actor.serialize-creators = on") {

  import akka.actor.PropsCreationSpec._

  "Props" must {
    "work with creator" in {
      val p = Props(new OneParamActor(null))
      system.actorOf(p)
    }
    "work with classOf" in {
      val p = Props(classOf[OneParamActor], null)
      system.actorOf(p)
    }
    "work with classOf + 1 `null` param" in {
      val p = Props(classOf[OneParamActor], null)
      system.actorOf(p)
    }
    "work with classOf + 2 `null` params" in {
      val p = Props(classOf[TwoParamActor], null, null)
      system.actorOf(p)
    }
  }

  "Props Java API" must {
    "work with create(creator)" in {
      @silent
      val p = Props.create(OneParamActorCreator)
      system.actorOf(p)
    }
    "work with create(class, param)" in {
      val p = Props.create(classOf[OneParamActor], null)
      system.actorOf(p)
    }
  }

}
