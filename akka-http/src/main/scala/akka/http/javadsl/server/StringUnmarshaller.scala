package akka.http.javadsl.server

import akka.http.scaladsl.unmarshalling.PredefinedFromStringUnmarshallers._
import akka.http.scaladsl.unmarshalling
import scala.concurrent.ExecutionContext
import Unmarshaller.wrap
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

