/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed

import akka.testkit.AkkaSpec

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.Future
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import akka.util.Timeout

import scala.reflect.ClassTag
import akka.actor.ActorInitializationException

import language.existentials
import akka.testkit.TestEvent.Mute
import akka.actor.typed.scaladsl.Actor._
import org.scalactic.CanEqual

import scala.util.control.{ NoStackTrace, NonFatal }
import akka.actor.typed.scaladsl.AskPattern

object TypedSpec {

  def getCallerName(clazz: Class[_]): String = {
    val s = (Thread.currentThread.getStackTrace map (_.getClassName) drop 1)
      .dropWhile(_ matches "(java.lang.Thread|.*TypedSpec.?$)")
    val reduced = s.lastIndexWhere(_ == clazz.getName) match {
      case -1 ⇒ s
      case z  ⇒ s drop (z + 1)
    }
    reduced.head.replaceFirst(""".*\.""", "").replaceAll("[^a-zA-Z_0-9]", "_")
  }
}

