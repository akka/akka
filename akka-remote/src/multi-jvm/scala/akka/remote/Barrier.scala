/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

trait Barrier {
  def await() = { enter(); leave() }

  def apply(body: ⇒ Unit) {
    enter()
    body
    leave()
  }

  def enter(): Unit

  def leave(): Unit
}
