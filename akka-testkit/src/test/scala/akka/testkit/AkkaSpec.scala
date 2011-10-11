/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.AkkaApplication
import akka.actor.{ Actor, ActorRef, Props }
import akka.dispatch.MessageDispatcher

abstract class AkkaSpec(_application: AkkaApplication = AkkaApplication())
  extends TestKit(_application) with WordSpec with MustMatchers {

  def createActor(props: Props): ActorRef = app.createActor(props)

  def createActor[T <: Actor](clazz: Class[T]): ActorRef = createActor(Props(clazz))

  def createActor[T <: Actor: Manifest]: ActorRef = createActor(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]])

  def createActor[T <: Actor](factory: ⇒ T): ActorRef = createActor(Props(factory))

  def spawn(body: ⇒ Unit)(implicit dispatcher: MessageDispatcher) {
    createActor(Props(ctx ⇒ { case "go" ⇒ try body finally ctx.self.stop() }).withDispatcher(dispatcher)) ! "go"
  }
}