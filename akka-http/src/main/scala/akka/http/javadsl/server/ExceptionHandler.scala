/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

trait ExceptionHandler {
  def handle(exception: RuntimeException): Route
}
