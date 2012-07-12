/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import scala.runtime.{ BoxedUnit, AbstractPartialFunction }
import akka.japi.{ Function ⇒ JFunc, Option ⇒ JOption }
import scala.concurrent.{ Future, Promise, ExecutionContext }
import java.lang.{ Iterable ⇒ JIterable }
import java.util.{ LinkedList ⇒ JLinkedList }
import java.util.concurrent.{ ExecutionException, Callable, TimeoutException }

/**
 * Futures is the Java API for Futures and Promises
 */
object Futures {

  /**
   * Java API, equivalent to Future.apply
   */
  def future[T](body: Callable[T], executor: ExecutionContext): Future[T] = Future(body.call)(executor)

  /**
   * Java API, equivalent to Promise.apply
   */
  def promise[T](executor: ExecutionContext): Promise[T] = Promise[T]()

  /**
   * Java API, creates an already completed Promise with the specified exception
   */
  def failed[T](exception: Throwable, executor: ExecutionContext): Future[T] = Promise.failed(exception).future

  /**
   * Java API, Creates an already completed Promise with the specified result
   */
  def successful[T](result: T, executor: ExecutionContext): Future[T] = Promise.successful(result).future

  /**
   * Java API.
   * Returns a Future that will hold the optional result of the first Future with a result that matches the predicate
   */
  def find[T <: AnyRef](futures: JIterable[Future[T]], predicate: JFunc[T, java.lang.Boolean], executor: ExecutionContext): Future[JOption[T]] = {
    Future.find[T]((scala.collection.JavaConversions.iterableAsScalaIterable(futures)))(predicate.apply(_))(executor).map(JOption.fromScalaOption(_))
  }

  /**
   * Java API.
   * Returns a Future to the result of the first future in the list that is completed
   */
  def firstCompletedOf[T <: AnyRef](futures: JIterable[Future[T]], executor: ExecutionContext): Future[T] =
    Future.firstCompletedOf(scala.collection.JavaConversions.iterableAsScalaIterable(futures))(executor)

  /**
   * Java API
   * A non-blocking fold over the specified futures, with the start value of the given zero.
   * The fold is performed on the thread where the last future is completed,
   * the result will be the first failure of any of the futures, or any failure in the actual fold,
   * or the result of the fold.
   */
  def fold[T <: AnyRef, R <: AnyRef](zero: R, futures: JIterable[Future[T]], fun: akka.japi.Function2[R, T, R], executor: ExecutionContext): Future[R] =
    Future.fold(scala.collection.JavaConversions.iterableAsScalaIterable(futures))(zero)(fun.apply)(executor)

  /**
   * Java API.
   Reduces the results of the supplied futures and binary function.
   */
  def reduce[T <: AnyRef, R >: T](futures: JIterable[Future[T]], fun: akka.japi.Function2[R, T, R], executor: ExecutionContext): Future[R] =
    Future.reduce[T, R](scala.collection.JavaConversions.iterableAsScalaIterable(futures))(fun.apply)(executor)

  /**
   * Java API.
   * Simple version of Future.traverse. Transforms a JIterable[Future[A]] into a Future[JIterable[A]].
   * Useful for reducing many Futures into a single Future.
   */
  def sequence[A](in: JIterable[Future[A]], executor: ExecutionContext): Future[JIterable[A]] = {
    implicit val d = executor
    scala.collection.JavaConversions.iterableAsScalaIterable(in).foldLeft(Future(new JLinkedList[A]()))((fr, fa) ⇒
      for (r ← fr; a ← fa) yield {
        r add a
        r
      })
  }

  /**
   * Java API.
   * Transforms a JIterable[A] into a Future[JIterable[B]] using the provided Function A ⇒ Future[B].
   * This is useful for performing a parallel map. For example, to apply a function to all items of a list
   * in parallel.
   */
  def traverse[A, B](in: JIterable[A], fn: JFunc[A, Future[B]], executor: ExecutionContext): Future[JIterable[B]] = {
    implicit val d = executor
    scala.collection.JavaConversions.iterableAsScalaIterable(in).foldLeft(Future(new JLinkedList[B]())) { (fr, a) ⇒
      val fb = fn(a)
      for (r ← fr; b ← fb) yield { r add b; r }
    }
  }

  /**
   * Signals that the current thread of execution will potentially engage
   * an action that will take a non-trivial amount of time, perhaps by using blocking.IO or using a lot of CPU time,
   * giving the system a chance to spawn new threads, reuse old threads or otherwise,
   * to prevent starvation and/or unfairness.
   *
   * Assures that any Future tasks initiated in the current thread will be
   * executed asynchronously, including any tasks currently queued to be
   * executed in the current thread. This is needed if the current task may
   * block, causing delays in executing the remaining tasks which in some
   * cases may cause a deadlock.
   *
   * Usage: Call this method in a callback (map, flatMap etc also count) to a Future,
   * if you will be doing blocking in the callback.
   *
   * Note: Calling 'Await.result(future)' or 'Await.ready(future)' will automatically trigger this method.
   *
   */
  def blocking(): Unit = scala.concurrent.impl.InternalFutureUtil.releaseFutureStack(ExecutionContext.defaultExecutionContext) //FIXME NOT CORRECT EC
}

/**
 * This class contains bridge classes between Scala and Java.
 * Internal use only.
 */
object japi {
  @deprecated("Do not use this directly, use subclasses of this", "2.0")
  class CallbackBridge[-T] extends AbstractPartialFunction[T, BoxedUnit] {
    override final def isDefinedAt(t: T): Boolean = true
    override final def apply(t: T): BoxedUnit = {
      internal(t)
      BoxedUnit.UNIT
    }
    protected def internal(result: T): Unit = ()
  }

  @deprecated("Do not use this directly, use 'Recover'", "2.0")
  class RecoverBridge[+T] extends AbstractPartialFunction[Throwable, T] {
    override final def isDefinedAt(t: Throwable): Boolean = true
    override final def apply(t: Throwable): T = internal(t)
    protected def internal(result: Throwable): T = null.asInstanceOf[T]
  }

  @deprecated("Do not use this directly, use subclasses of this", "2.0")
  class BooleanFunctionBridge[-T] extends scala.Function1[T, Boolean] {
    override final def apply(t: T): Boolean = internal(t)
    protected def internal(result: T): Boolean = false
  }

  @deprecated("Do not use this directly, use subclasses of this", "2.0")
  class UnitFunctionBridge[-T] extends (T ⇒ BoxedUnit) {
    final def apply$mcLJ$sp(l: Long): BoxedUnit = { internal(l.asInstanceOf[T]); BoxedUnit.UNIT }
    final def apply$mcLI$sp(i: Int): BoxedUnit = { internal(i.asInstanceOf[T]); BoxedUnit.UNIT }
    final def apply$mcLF$sp(f: Float): BoxedUnit = { internal(f.asInstanceOf[T]); BoxedUnit.UNIT }
    final def apply$mcLD$sp(d: Double): BoxedUnit = { internal(d.asInstanceOf[T]); BoxedUnit.UNIT }
    override final def apply(t: T): BoxedUnit = { internal(t); BoxedUnit.UNIT }
    protected def internal(result: T): Unit = ()
  }
}

/**
 * Callback for when a Future is completed successfully
 * SAM (Single Abstract Method) class
 *
 * Java API
 */
abstract class OnSuccess[-T] extends japi.CallbackBridge[T] {
  protected final override def internal(result: T) = onSuccess(result)

  /**
   * This method will be invoked once when/if a Future that this callback is registered on
   * becomes successfully completed
   */
  @throws(classOf[Throwable])
  def onSuccess(result: T): Unit
}

/**
 * Callback for when a Future is completed with a failure
 * SAM (Single Abstract Method) class
 *
 * Java API
 */
abstract class OnFailure extends japi.CallbackBridge[Throwable] {
  protected final override def internal(failure: Throwable) = onFailure(failure)

  /**
   * This method will be invoked once when/if a Future that this callback is registered on
   * becomes completed with a failure
   */
  @throws(classOf[Throwable])
  def onFailure(failure: Throwable): Unit
}

/**
 * Callback for when a Future is completed with either failure or a success
 * SAM (Single Abstract Method) class
 *
 * Java API
 */
abstract class OnComplete[-T] extends japi.CallbackBridge[Either[Throwable, T]] {
  protected final override def internal(value: Either[Throwable, T]): Unit = value match {
    case Left(t)  ⇒ onComplete(t, null.asInstanceOf[T])
    case Right(r) ⇒ onComplete(null, r)
  }

  /**
   * This method will be invoked once when/if a Future that this callback is registered on
   * becomes completed with a failure or a success.
   * In the case of success then "failure" will be null, and in the case of failure the "success" will be null.
   */
  @throws(classOf[Throwable])
  def onComplete(failure: Throwable, success: T): Unit
}

/**
 * Callback for the Future.recover operation that conditionally turns failures into successes.
 *
 * SAM (Single Abstract Method) class
 *
 * Java API
 */
abstract class Recover[+T] extends japi.RecoverBridge[T] {
  protected final override def internal(result: Throwable): T = recover(result)

  /**
   * This method will be invoked once when/if the Future this recover callback is registered on
   * becomes completed with a failure.
   *
   * @return a successful value for the passed in failure
   * @throws the passed in failure to propagate it.
   *
   * Java API
   */
  @throws(classOf[Throwable])
  def recover(failure: Throwable): T
}

/**
 * <i><b>Java API (not recommended):</b></i>
 * Callback for the Future.filter operation that creates a new Future which will
 * conditionally contain the success of another Future.
 *
 * Unfortunately it is not possible to express the type of a Scala filter in
 * Java: Function1[T, Boolean], where “Boolean” is the primitive type. It is
 * possible to use `Future.filter` by constructing such a function indirectly:
 *
 * {{{
 * import static akka.dispatch.Filter.filterOf;
 * Future<String> f = ...;
 * f.filter(filterOf(new Function<String, Boolean>() {
 *   @Override
 *   public Boolean apply(String s) {
 *     ...
 *   }
 * }));
 * }}}
 *
 * However, `Future.filter` exists mainly to support Scala’s for-comprehensions,
 * thus Java users should prefer `Future.map`, translating non-matching values
 * to failure cases.
 */
object Filter {
  def filterOf[T](f: akka.japi.Function[T, java.lang.Boolean]): (T ⇒ Boolean) =
    new Function1[T, Boolean] { def apply(result: T): Boolean = f(result).booleanValue() }
}

/**
 * Callback for the Future.foreach operation that will be invoked if the Future that this callback
 * is registered on becomes completed with a success. This method is essentially the same operation
 * as onSuccess.
 *
 * SAM (Single Abstract Method) class
 * Java API
 */
abstract class Foreach[-T] extends japi.UnitFunctionBridge[T] {
  override final def internal(t: T): Unit = each(t)

  /**
   * This method will be invoked once when/if a Future that this callback is registered on
   * becomes successfully completed
   */
  @throws(classOf[Throwable])
  def each(result: T): Unit
}

/**
 * Callback for the Future.map and Future.flatMap operations that will be invoked
 * if the Future that this callback is registered on becomes completed with a success.
 * This callback is the equivalent of an akka.japi.Function
 *
 * Override "apply" normally, or "checkedApply" if you need to throw checked exceptions.
 *
 * SAM (Single Abstract Method) class
 *
 * Java API
 */
abstract class Mapper[-T, +R] extends scala.runtime.AbstractFunction1[T, R] {

  /**
   * Override this method to perform the map operation, by default delegates to "checkedApply"
   * which by default throws an UnsupportedOperationException.
   */
  def apply(parameter: T): R = checkedApply(parameter)

  /**
   * Override this method if you need to throw checked exceptions
   *
   * @throws UnsupportedOperation by default
   */
  @throws(classOf[Throwable])
  def checkedApply(parameter: T): R = throw new UnsupportedOperationException("Mapper.checkedApply has not been implemented")
}
