/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import akka.config.Configuration
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.AkkaApplication
import akka.actor.{ Actor, ActorRef, Props }
import akka.dispatch.MessageDispatcher

abstract class AkkaSpec(_application: AkkaApplication = AkkaApplication())
  extends TestKit(_application) with WordSpec with MustMatchers {

  def this(config: Configuration) = this(new AkkaApplication(getClass.getSimpleName, AkkaApplication.defaultConfig ++ config))

  def actorOf(props: Props): ActorRef = app.actorOf(props)

  def actorOf[T <: Actor](clazz: Class[T]): ActorRef = actorOf(Props(clazz))

  def actorOf[T <: Actor: Manifest]: ActorRef = actorOf(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]])

  def actorOf[T <: Actor](factory: ⇒ T): ActorRef = actorOf(Props(factory))

  def spawn(body: ⇒ Unit)(implicit dispatcher: MessageDispatcher) {
    actorOf(Props(ctx ⇒ { case "go" ⇒ try body finally ctx.self.stop() }).withDispatcher(dispatcher)) ! "go"
  }
}