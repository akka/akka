/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import scala.util.Try
import scala.util.control.NoStackTrace
import scala.util.{ Failure => ScalaFailure }
import scala.util.{ Success => ScalaSuccess }

/**
 * Generic top level message type for signalling responses that may fail or succeed. Convenient to use together with the
 * `askWithStatus` ask variants.
 *
 * Create using the factory methods [[ReplyWithStatus#succes]] and [[ReplyWithStatus#error]].
 *
 * Akka contains predefined serializers for the wrapper type and the textual error messages.
 *
 * @tparam T the type of value a successful reply would have
 */
final case class ReplyWithStatus[+T] private (private val status: Try[T]) {

  /**
   * Java API: in the case of a successful reply returns the value, if the reply was not successful the exception
   * the failure was created with is thrown
   */
  def getValue: T = status.get

  /**
   * Java API: returns the exception if the reply is a failure, or throws an exeption if not.
   */
  def getError: Throwable = status match {
    case ScalaFailure(ex) => ex
    case _                => throw new IllegalArgumentException("Expected reply to be a failure, but was a success")
  }

  def isFailure: Boolean = status.isFailure
  def isSuccess: Boolean = status.isSuccess

  override def toString: String = status match {
    case ScalaSuccess(t)  => s"ReplyWithStatus.Success($t)"
    case ScalaFailure(ex) => s"ReplyWithStatus.Error(${ex.getMessage})"
  }

}

object ReplyWithStatus {

  /**
   * Java API: Create a successful reply containing `value`
   */
  def success[T](value: T): ReplyWithStatus[T] = new ReplyWithStatus(ScalaSuccess(value))

  /**
   * Java API: Create an status response with a error message describing why the request was failed or denied.
   */
  def error[T](errorMessage: String): ReplyWithStatus[T] = Error(errorMessage)

  /**
   * Java API: Create an error response with a user defined [[Throwable]].
   *
   * Prefer the string based error response over this one when possible to avoid tightly coupled logic across
   * actors and passing internal failure details on to callers that can not do much to handle them.
   *
   * For cases where types are needed to identify failures and behave differently enumerating them with a specific
   * set of response messages may be a better alternative to encoding them as generic exceptions.
   *
   * Also note that Akka does not contain pre-build serializers for arbitrary exceptions.
   */
  def error[T](exception: Throwable): ReplyWithStatus[T] = Error(exception)

  /**
   * Carrier exception used for textual error descriptions.
   *
   * Not meant for usage outside of [[ReplyWithStatus]].
   */
  final case class ErrorMessage(private val errorMessage: String)
      extends RuntimeException(errorMessage)
      with NoStackTrace {
    override def toString: String = errorMessage
  }

  /**
   * Scala API for creation and pattern matching a successful response.
   *
   * For example:
   * ```
   *   case ReplyWithStatus.Success(value: String) => ...
   * ```
   */
  object Success {

    /**
     * Scala API: Create a successful reply containing `value`
     */
    def apply[T](value: T): ReplyWithStatus[T] = new ReplyWithStatus(ScalaSuccess(value))
    def unapply(status: ReplyWithStatus[Any]): Option[Any] =
      if (status.isSuccess) Some(status.getValue)
      else None
  }

  /**
   * Scala API for creating and pattern matching an error response
   *
   * For example:
   * ```
   *   case ReplyWithStatus.Error(exception) => ...
   * ```
   */
  object Error {

    /**
     * Scala API: Create an status response with a error message describing why the request was failed or denied.
     */
    def apply[T](errorMessage: String): ReplyWithStatus[T] = error(new ErrorMessage(errorMessage))

    /**
     * Scala API: Create an error response with a user defined [[Throwable]].
     *
     * Prefer the string based error response over this one when possible to avoid tightly coupled logic across
     * actors and passing internal failure details on to callers that can not do much to handle them.
     *
     * For cases where types are needed to identify failures and behave differently enumerating them with a specific
     * set of response messages may be a better alternative to encoding them as generic exceptions.
     *
     * Also note that Akka does not contain pre-build serializers for arbitrary exceptions.
     */
    def apply[T](exception: Throwable): ReplyWithStatus[T] = new ReplyWithStatus(ScalaFailure(exception))
    def unapply(status: ReplyWithStatus[_]): Option[Throwable] =
      if (status.isFailure) Some(status.getError)
      else None
  }

}
