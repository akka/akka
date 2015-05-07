/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server

trait ExceptionHandler {
  def handle(exception: RuntimeException): Route
}
