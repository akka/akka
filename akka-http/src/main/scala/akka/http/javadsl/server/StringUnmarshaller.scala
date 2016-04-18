/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

import java.util.concurrent.CompletionStage

object StringUnmarshaller {
  /**
   * Turns the given asynchronous function into an unmarshaller from String to B.
   */
  def async[B](f: java.util.function.Function[String, CompletionStage[B]]): Unmarshaller[String, B] = Unmarshaller.async(f)

  /**
   * Turns the given function into an unmarshaller from String to B.
   */
  def sync[B](f: java.util.function.Function[String, B]): Unmarshaller[String, B] = Unmarshaller.sync(f)
}

/**
 * INTERNAL API
 */
private[server] object StringUnmarshallerPredef extends akka.http.scaladsl.unmarshalling.PredefinedFromStringUnmarshallers {

}

