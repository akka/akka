/*
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server

trait ExceptionHandler {
  def handle(exception: RuntimeException): Route
}
