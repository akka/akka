/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import akka.AkkaException
import akka.event.EventHandler
import akka.actor.{ Actor, ForwardableChannel, UntypedChannel, Scheduler, Timeout, ExceptionChannel }
import scala.Option
import akka.japi.{ Procedure, Function ⇒ JFunc, Option ⇒ JOption }

import scala.util.continuations._

import java.util.concurrent.{ ConcurrentLinkedQueue, TimeUnit, Callable }
import java.util.concurrent.TimeUnit.{ NANOSECONDS ⇒ NANOS, MILLISECONDS ⇒ MILLIS }
import java.lang.{ Iterable ⇒ JIterable }
import java.util.{ LinkedList ⇒ JLinkedList }

import scala.annotation.tailrec
import scala.collection.mutable.Stack
import akka.util.{ Switch, Duration, BoxedType }
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference, AtomicBoolean }

class FutureTimeoutException(message: String, cause: Throwable = null) extends AkkaException(message, cause) {
  def this(message: String) = this(message, null)
}

object Futures {

  /**
   * Java API, equivalent to Future.apply
   */
  def future[T](body: Callable[T]): Future[T] =
    Future(body.call)

  /**
   * Java API, equivalent to Future.apply
   */
  def future[T](body: Callable[T], timeout: Timeout): Future[T] =
    Future(body.call, timeout)

  /**
   * Java API, equivalent to Future.apply
   */
  def future[T](body: Callable[T], timeout: Long): Future[T] =
    Future(body.call, timeout)

  /**
   * Java API, equivalent to Future.apply
   */
  def future[T](body: Callable[T], dispatcher: MessageDispatcher): Future[T] =
    Future(body.call)(dispatcher)

  /**
   * Java API, equivalent to Future.apply
   */
  def future[T](body: Callable[T], timeout: Timeout, dispatcher: MessageDispatcher): Future[T] =
    Future(body.call)(dispatcher, timeout)

  /**
   * Java API, equivalent to Future.apply
   */
  def future[T](body: Callable[T], timeout: Long, dispatcher: MessageDispatcher): Future[T] =
    Future(body.call)(dispatcher, timeout)

  /**
   * Java API.
   * Returns a Future that will hold the optional result of the first Future with a result that matches the predicate
   */
  def find[T <: AnyRef](futures: JIterable[Future[T]], predicate: JFunc[T, java.lang.Boolean], timeout: Timeout): Future[JOption[T]] = {
    val pred: T ⇒ Boolean = predicate.apply(_)
    Future.find[T](pred, timeout)(scala.collection.JavaConversions.iterableAsScalaIterable(futures)).map(JOption.fromScalaOption(_))
  }

  /**
   * Java API.
   * Returns a Future to the result of the first future in the list that is completed
   */
  def firstCompletedOf[T <: AnyRef](futures: JIterable[Future[T]], timeout: Timeout): Future[T] =
    Future.firstCompletedOf(scala.collection.JavaConversions.iterableAsScalaIterable(futures), timeout)

  /**
   * Java API
   * A non-blocking fold over the specified futures.
   * The fold is performed on the thread where the last future is completed,
   * the result will be the first failure of any of the futures, or any failure in the actual fold,
   * or the result of the fold.
   */
  def fold[T <: AnyRef, R <: AnyRef](zero: R, timeout: Timeout, futures: java.lang.Iterable[Future[T]], fun: akka.japi.Function2[R, T, R]): Future[R] =
    Future.fold(zero, timeout)(scala.collection.JavaConversions.iterableAsScalaIterable(futures))(fun.apply _)

  def fold[T <: AnyRef, R <: AnyRef](zero: R, timeout: Long, futures: java.lang.Iterable[Future[T]], fun: akka.japi.Function2[R, T, R]): Future[R] = fold(zero, timeout: Timeout, futures, fun)

  def fold[T <: AnyRef, R <: AnyRef](zero: R, futures: java.lang.Iterable[Future[T]], fun: akka.japi.Function2[R, T, R]): Future[R] = fold(zero, Timeout.default, futures, fun)

  /**
   * Java API.
   * Initiates a fold over the supplied futures where the fold-zero is the result value of the Future that's completed first
   */
  def reduce[T <: AnyRef, R >: T](futures: java.lang.Iterable[Future[T]], timeout: Timeout, fun: akka.japi.Function2[R, T, T]): Future[R] =
    Future.reduce(scala.collection.JavaConversions.iterableAsScalaIterable(futures), timeout)(fun.apply _)

  def reduce[T <: AnyRef, R >: T](futures: java.lang.Iterable[Future[T]], timeout: Long, fun: akka.japi.Function2[R, T, T]): Future[R] = reduce(futures, timeout: Timeout, fun)

  /**
   * Java API.
   * Simple version of Future.traverse. Transforms a java.lang.Iterable[Future[A]] into a Future[java.lang.Iterable[A]].
   * Useful for reducing many Futures into a single Future.
   */
  def sequence[A](in: JIterable[Future[A]], timeout: Timeout): Future[JIterable[A]] =
    scala.collection.JavaConversions.iterableAsScalaIterable(in).foldLeft(Future(new JLinkedList[A]()))((fr, fa) ⇒
      for (r ← fr; a ← fa) yield {
        r add a
        r
      })

  /**
   * Java API.
   * Simple version of Futures.traverse. Transforms a java.lang.Iterable[Future[A]] into a Future[java.lang.Iterable[A]].
   * Useful for reducing many Futures into a single Future.
   */
  def sequence[A](in: JIterable[Future[A]]): Future[JIterable[A]] = sequence(in, Timeout.default)

  /**
   * Java API.
   * Transforms a java.lang.Iterable[A] into a Future[java.lang.Iterable[B]] using the provided Function A ⇒ Future[B].
   * This is useful for performing a parallel map. For example, to apply a function to all items of a list
   * in parallel.
   */
  def traverse[A, B](in: JIterable[A], timeout: Timeout, fn: JFunc[A, Future[B]]): Future[JIterable[B]] =
    scala.collection.JavaConversions.iterableAsScalaIterable(in).foldLeft(Future(new JLinkedList[B]())) { (fr, a) ⇒
      val fb = fn(a)
      for (r ← fr; b ← fb) yield {
        r add b
        r
      }
    }

  /**
   * Java API.
   * Transforms a java.lang.Iterable[A] into a Future[java.lang.Iterable[B]] using the provided Function A ⇒ Future[B].
   * This is useful for performing a parallel map. For example, to apply a function to all items of a list
   * in parallel.
   */
  def traverse[A, B](in: JIterable[A], fn: JFunc[A, Future[B]]): Future[JIterable[B]] = traverse(in, Timeout.default, fn)
}

object Future {

  /**
   * This method constructs and returns a Future that will eventually hold the result of the execution of the supplied body
   * The execution is performed by the specified Dispatcher.
   */
  def apply[T](body: ⇒ T)(implicit dispatcher: MessageDispatcher, timeout: Timeout = implicitly): Future[T] = {
    val promise = new DefaultPromise[T](timeout)
    dispatcher dispatchTask { () ⇒
      promise complete {
        try {
          Right(body)
        } catch {
          case e ⇒ Left(e)
        }
      }
    }
    promise
  }

  def apply[T](body: ⇒ T, timeout: Timeout)(implicit dispatcher: MessageDispatcher): Future[T] =
    apply(body)(dispatcher, timeout)

  def apply[T](body: ⇒ T, timeout: Duration)(implicit dispatcher: MessageDispatcher): Future[T] =
    apply(body)(dispatcher, timeout)

  def apply[T](body: ⇒ T, timeout: Long)(implicit dispatcher: MessageDispatcher): Future[T] =
    apply(body)(dispatcher, timeout)

  import scala.collection.mutable.Builder
  import scala.collection.generic.CanBuildFrom

  /**
   * Simple version of Futures.traverse. Transforms a Traversable[Future[A]] into a Future[Traversable[A]].
   * Useful for reducing many Futures into a single Future.
   */
  def sequence[A, M[_] <: Traversable[_]](in: M[Future[A]])(implicit cbf: CanBuildFrom[M[Future[A]], A, M[A]], timeout: Timeout): Future[M[A]] =
    in.foldLeft(new KeptPromise(Right(cbf(in))): Future[Builder[A, M[A]]])((fr, fa) ⇒ for (r ← fr; a ← fa.asInstanceOf[Future[A]]) yield (r += a)).map(_.result)

  def sequence[A, M[_] <: Traversable[_]](timeout: Timeout)(in: M[Future[A]])(implicit cbf: CanBuildFrom[M[Future[A]], A, M[A]]): Future[M[A]] =
    sequence(in)(cbf, timeout)

  /**
   * Returns a Future to the result of the first future in the list that is completed
   */
  def firstCompletedOf[T](futures: Iterable[Future[T]], timeout: Timeout = Timeout.never): Future[T] = {
    val futureResult = new DefaultPromise[T](timeout)

    val completeFirst: Future[T] ⇒ Unit = _.value.foreach(futureResult complete _)
    futures.foreach(_ onComplete completeFirst)

    futureResult
  }

  /**
   * Returns a Future that will hold the optional result of the first Future with a result that matches the predicate
   */
  def find[T](predicate: T ⇒ Boolean, timeout: Timeout = Timeout.default)(futures: Iterable[Future[T]]): Future[Option[T]] = {
    if (futures.isEmpty) new KeptPromise[Option[T]](Right(None))
    else {
      val result = new DefaultPromise[Option[T]](timeout)
      val ref = new AtomicInteger(futures.size)
      val search: Future[T] ⇒ Unit = f ⇒ try {
        f.result.filter(predicate).foreach(r ⇒ result completeWithResult Some(r))
      } finally {
        if (ref.decrementAndGet == 0)
          result completeWithResult None
      }
      futures.foreach(_ onComplete search)

      result
    }
  }

  /**
   * A non-blocking fold over the specified futures.
   * The fold is performed on the thread where the last future is completed,
   * the result will be the first failure of any of the futures, or any failure in the actual fold,
   * or the result of the fold.
   * Example:
   * <pre>
   *   val result = Futures.fold(0)(futures)(_ + _).await.result
   * </pre>
   */
  def fold[T, R](zero: R, timeout: Timeout = Timeout.default)(futures: Iterable[Future[T]])(foldFun: (R, T) ⇒ R): Future[R] = {
    if (futures.isEmpty) {
      new KeptPromise[R](Right(zero))
    } else {
      val result = new DefaultPromise[R](timeout)
      val results = new ConcurrentLinkedQueue[T]()
      val done = new Switch(false)
      val allDone = futures.size

      val aggregate: Future[T] ⇒ Unit = f ⇒ if (done.isOff && !result.isCompleted) { //TODO: This is an optimization, is it premature?
        f.value.get match {
          case Right(value) ⇒
            val added = results add value
            if (added && results.size == allDone) { //Only one thread can get here
              if (done.switchOn) {
                try {
                  val i = results.iterator
                  var currentValue = zero
                  while (i.hasNext) { currentValue = foldFun(currentValue, i.next) }
                  result completeWithResult currentValue
                } catch {
                  case e: Exception ⇒
                    EventHandler.error(e, this, e.getMessage)
                    result completeWithException e
                } finally {
                  results.clear
                }
              }
            }
          case Left(exception) ⇒
            if (done.switchOn) {
              result completeWithException exception
              results.clear
            }
        }
      }

      futures foreach { _ onComplete aggregate }
      result
    }
  }

  /**
   * Initiates a fold over the supplied futures where the fold-zero is the result value of the Future that's completed first
   * Example:
   * <pre>
   *   val result = Futures.reduce(futures)(_ + _).await.result
   * </pre>
   */
  def reduce[T, R >: T](futures: Iterable[Future[T]], timeout: Timeout = Timeout.default)(op: (R, T) ⇒ T): Future[R] = {
    if (futures.isEmpty)
      new KeptPromise[R](Left(new UnsupportedOperationException("empty reduce left")))
    else {
      val result = new DefaultPromise[R](timeout)
      val seedFound = new AtomicBoolean(false)
      val seedFold: Future[T] ⇒ Unit = f ⇒ {
        if (seedFound.compareAndSet(false, true)) { //Only the first completed should trigger the fold
          f.value.get match {
            case Right(value)    ⇒ result.completeWith(fold(value, timeout)(futures.filterNot(_ eq f))(op))
            case Left(exception) ⇒ result.completeWithException(exception)
          }
        }
      }
      for (f ← futures) f onComplete seedFold //Attach the listener to the Futures
      result
    }
  }

  /**
   * Transforms a Traversable[A] into a Future[Traversable[B]] using the provided Function A ⇒ Future[B].
   * This is useful for performing a parallel map. For example, to apply a function to all items of a list
   * in parallel:
   * <pre>
   * val myFutureList = Futures.traverse(myList)(x ⇒ Future(myFunc(x)))
   * </pre>
   */
  def traverse[A, B, M[_] <: Traversable[_]](in: M[A])(fn: A ⇒ Future[B])(implicit cbf: CanBuildFrom[M[A], B, M[B]], timeout: Timeout): Future[M[B]] =
    in.foldLeft(new KeptPromise(Right(cbf(in))): Future[Builder[B, M[B]]]) { (fr, a) ⇒
      val fb = fn(a.asInstanceOf[A])
      for (r ← fr; b ← fb) yield (r += b)
    }.map(_.result)

  def traverse[A, B, M[_] <: Traversable[_]](in: M[A], timeout: Timeout)(fn: A ⇒ Future[B])(implicit cbf: CanBuildFrom[M[A], B, M[B]]): Future[M[B]] =
    traverse(in)(fn)(cbf, timeout)

  /**
   * Captures a block that will be transformed into 'Continuation Passing Style' using Scala's Delimited
   * Continuations plugin.
   *
   * Within the block, the result of a Future may be accessed by calling Future.apply. At that point
   * execution is suspended with the rest of the block being stored in a continuation until the result
   * of the Future is available. If an Exception is thrown while processing, it will be contained
   * within the resulting Future.
   *
   * This allows working with Futures in an imperative style without blocking for each result.
   *
   * Completing a Future using 'Promise << Future' will also suspend execution until the
   * value of the other Future is available.
   *
   * The Delimited Continuations compiler plugin must be enabled in order to use this method.
   */
  def flow[A](body: ⇒ A @cps[Future[Any]])(implicit dispatcher: MessageDispatcher, timeout: Timeout): Future[A] = {
    val future = Promise[A](timeout)
    dispatchTask({ () ⇒
      (reify(body) foreachFull (future completeWithResult, future completeWithException): Future[Any]) onException {
        case e: Exception ⇒ future completeWithException e
      }
    }, true)
    future
  }

  private val _taskStack = new ThreadLocal[Option[Stack[() ⇒ Unit]]]() {
    override def initialValue = None
  }

  private[akka] def dispatchTask(task: () ⇒ Unit, force: Boolean = false)(implicit dispatcher: MessageDispatcher): Unit =
    _taskStack.get match {
      case Some(taskStack) if !force ⇒ taskStack push task
      case _ ⇒
        dispatcher dispatchTask { () ⇒
          try {
            val taskStack = Stack[() ⇒ Unit](task)
            _taskStack set Some(taskStack)
            while (taskStack.nonEmpty) {
              val next = taskStack.pop()
              try {
                next.apply()
              } catch {
                case e ⇒ // TODO FIXME: Throwable or Exception, log or do what?
              }
            }
          } finally { _taskStack set None }
        }
    }
}

sealed trait Future[+T] extends japi.Future[T] {

  implicit def dispatcher: MessageDispatcher

  /**
   * For use only within a Future.flow block or another compatible Delimited Continuations reset block.
   *
   * Returns the result of this Future without blocking, by suspending execution and storing it as a
   * continuation until the result is available.
   */
  def apply()(implicit timeout: Timeout): T @cps[Future[Any]] = shift(this flatMap (_: T ⇒ Future[Any]))

  /**
   * Blocks awaiting completion of this Future, then returns the resulting value,
   * or throws the completed exception
   *
   * Scala & Java API
   *
   * throws FutureTimeoutException if this Future times out when waiting for completion
   */
  def get: T = this.await.resultOrException.get

  /**
   * Blocks the current thread until the Future has been completed or the
   * timeout has expired. In the case of the timeout expiring a
   * FutureTimeoutException will be thrown.
   */
  def await: Future[T]

  /**
   * Blocks the current thread until the Future has been completed or the
   * timeout has expired, additionally bounding the waiting period according to
   * the <code>atMost</code> parameter. The timeout will be the lesser value of
   * 'atMost' and the timeout supplied at the constructuion of this Future.  In
   * the case of the timeout expiring a FutureTimeoutException will be thrown.
   * Other callers of this method are not affected by the additional bound
   * imposed by <code>atMost</code>.
   */
  def await(atMost: Duration): Future[T]

  /**
   * Await completion of this Future and return its value if it conforms to A's
   * erased type. Will throw a ClassCastException if the value does not
   * conform, or any exception the Future was completed with. Will return None
   * in case of a timeout.
   */
  def as[A](implicit m: Manifest[A]): Option[A] = {
    try await catch { case _: FutureTimeoutException ⇒ }
    value match {
      case None           ⇒ None
      case Some(Left(ex)) ⇒ throw ex
      case Some(Right(v)) ⇒ Some(BoxedType(m.erasure).cast(v).asInstanceOf[A])
    }
  }

  /**
   * Await completion of this Future and return its value if it conforms to A's
   * erased type, None otherwise. Will throw any exception the Future was
   * completed with. Will return None in case of a timeout.
   */
  def asSilently[A](implicit m: Manifest[A]): Option[A] = {
    try await catch { case _: FutureTimeoutException ⇒ }
    value match {
      case None           ⇒ None
      case Some(Left(ex)) ⇒ throw ex
      case Some(Right(v)) ⇒
        try Some(BoxedType(m.erasure).cast(v).asInstanceOf[A])
        catch { case _: ClassCastException ⇒ None }
    }
  }

  /**
   * Tests whether this Future has been completed.
   */
  final def isCompleted: Boolean = value.isDefined

  /**
   * Tests whether this Future's timeout has expired.
   *
   * Note that an expired Future may still contain a value, or it may be
   * completed with a value.
   */
  def isExpired: Boolean

  def timeout: Timeout

  /**
   * This Future's timeout in nanoseconds.
   */
  def timeoutInNanos = if (timeout.duration.isFinite) timeout.duration.toNanos else Long.MaxValue

  /**
   * The contained value of this Future. Before this Future is completed
   * the value will be None. After completion the value will be Some(Right(t))
   * if it contains a valid result, or Some(Left(error)) if it contains
   * an exception.
   */
  def value: Option[Either[Throwable, T]]

  /**
   * Returns the successful result of this Future if it exists.
   */
  final def result: Option[T] = value match {
    case Some(Right(r)) ⇒ Some(r)
    case _              ⇒ None
  }

  /**
   * Returns the contained exception of this Future if it exists.
   */
  final def exception: Option[Throwable] = value match {
    case Some(Left(e)) ⇒ Some(e)
    case _             ⇒ None
  }

  /**
   * When this Future is completed, apply the provided function to the
   * Future. If the Future has already been completed, this will apply
   * immediately.
   */
  def onComplete(func: Future[T] ⇒ Unit): this.type

  /**
   * When the future is completed with a valid result, apply the provided
   * PartialFunction to the result.
   * <pre>
   *   future onResult {
   *     case Foo ⇒ target ! "foo"
   *     case Bar ⇒ target ! "bar"
   *   }
   * </pre>
   */
  final def onResult(pf: PartialFunction[T, Unit]): this.type = onComplete {
    _.value match {
      case Some(Right(r)) if pf isDefinedAt r ⇒ pf(r)
      case _                                  ⇒
    }
  }

  /**
   * When the future is completed with an exception, apply the provided
   * PartialFunction to the exception.
   * <pre>
   *   future onException {
   *     case NumberFormatException ⇒ target ! "wrong format"
   *   }
   * </pre>
   */
  final def onException(pf: PartialFunction[Throwable, Unit]): this.type = onComplete {
    _.value match {
      case Some(Left(ex)) if pf isDefinedAt ex ⇒ pf(ex)
      case _                                   ⇒
    }
  }

  def onTimeout(func: Future[T] ⇒ Unit): this.type

  def orElse[A >: T](fallback: ⇒ A): Future[A]

  /**
   * Creates a new Future that will handle any matching Throwable that this
   * Future might contain. If there is no match, or if this Future contains
   * a valid result then the new Future will contain the same.
   * Example:
   * <pre>
   * Future(6 / 0) recover { case e: ArithmeticException ⇒ 0 } // result: 0
   * Future(6 / 0) recover { case e: NotFoundException   ⇒ 0 } // result: exception
   * Future(6 / 2) recover { case e: ArithmeticException ⇒ 0 } // result: 3
   * </pre>
   */
  final def recover[A >: T](pf: PartialFunction[Throwable, A])(implicit timeout: Timeout): Future[A] = {
    val future = new DefaultPromise[A](timeout)
    onComplete {
      _.value.get match {
        case Left(e) if pf isDefinedAt e ⇒ future.complete(try { Right(pf(e)) } catch { case x: Exception ⇒ Left(x) })
        case otherwise                   ⇒ future complete otherwise
      }
    }
    future
  }

  /**
   * Creates a new Future by applying a function to the successful result of
   * this Future. If this Future is completed with an exception then the new
   * Future will also contain this exception.
   * Example:
   * <pre>
   * val future1 = for {
   *   a: Int    <- actor ? "Hello" // returns 5
   *   b: String <- actor ? a       // returns "10"
   *   c: String <- actor ? 7       // returns "14"
   * } yield b + "-" + c
   * </pre>
   */
  final def map[A](f: T ⇒ A)(implicit timeout: Timeout): Future[A] = {
    val future = new DefaultPromise[A](timeout)
    onComplete {
      _.value.get match {
        case l: Left[_, _] ⇒ future complete l.asInstanceOf[Either[Throwable, A]]
        case Right(res) ⇒
          future complete (try {
            Right(f(res))
          } catch {
            case e: Exception ⇒
              EventHandler.error(e, this, e.getMessage)
              Left(e)
          })
      }
    }
    future
  }

  /**
   * Creates a new Future[A] which is completed with this Future's result if
   * that conforms to A's erased type or a ClassCastException otherwise.
   */
  final def mapTo[A](implicit m: Manifest[A], timeout: Timeout = this.timeout): Future[A] = {
    val fa = new DefaultPromise[A](timeout)
    onComplete { ft ⇒
      fa complete (ft.value.get match {
        case l: Left[_, _] ⇒ l.asInstanceOf[Either[Throwable, A]]
        case Right(t) ⇒
          try {
            Right(BoxedType(m.erasure).cast(t).asInstanceOf[A])
          } catch {
            case e: ClassCastException ⇒ Left(e)
          }
      })
    }
    fa
  }

  /**
   * Creates a new Future by applying a function to the successful result of
   * this Future, and returns the result of the function as the new Future.
   * If this Future is completed with an exception then the new Future will
   * also contain this exception.
   * Example:
   * <pre>
   * val future1 = for {
   *   a: Int    <- actor ? "Hello" // returns 5
   *   b: String <- actor ? a       // returns "10"
   *   c: String <- actor ? 7       // returns "14"
   * } yield b + "-" + c
   * </pre>
   */
  final def flatMap[A](f: T ⇒ Future[A])(implicit timeout: Timeout): Future[A] = {
    val future = new DefaultPromise[A](timeout)

    onComplete {
      _.value.get match {
        case l: Left[_, _] ⇒ future complete l.asInstanceOf[Either[Throwable, A]]
        case Right(r) ⇒ try {
          future.completeWith(f(r))
        } catch {
          case e: Exception ⇒
            EventHandler.error(e, this, e.getMessage)
            future complete Left(e)
        }
      }
    }
    future
  }

  final def foreach(f: T ⇒ Unit): Unit = onComplete {
    _.value.get match {
      case Right(r) ⇒ f(r)
      case _        ⇒
    }
  }

  final def withFilter(p: T ⇒ Boolean)(implicit timeout: Timeout) = new FutureWithFilter[T](this, p)

  final class FutureWithFilter[+A](self: Future[A], p: A ⇒ Boolean)(implicit timeout: Timeout) {
    def foreach(f: A ⇒ Unit): Unit = self filter p foreach f
    def map[B](f: A ⇒ B): Future[B] = self filter p map f
    def flatMap[B](f: A ⇒ Future[B]): Future[B] = self filter p flatMap f
    def withFilter(q: A ⇒ Boolean): FutureWithFilter[A] = new FutureWithFilter[A](self, x ⇒ p(x) && q(x))
  }

  final def filter(p: T ⇒ Boolean)(implicit timeout: Timeout): Future[T] = {
    val future = new DefaultPromise[T](timeout)
    onComplete {
      _.value.get match {
        case l: Left[_, _] ⇒ future complete l.asInstanceOf[Either[Throwable, T]]
        case r @ Right(res) ⇒ future complete (try {
          if (p(res)) r else Left(new MatchError(res))
        } catch {
          case e: Exception ⇒
            EventHandler.error(e, this, e.getMessage)
            Left(e)
        })
      }
    }
    future
  }

  /**
   * Returns the current result, throws the exception if one has been raised, else returns None
   */
  final def resultOrException: Option[T] = value match {
    case Some(Left(e))  ⇒ throw e
    case Some(Right(r)) ⇒ Some(r)
    case _              ⇒ None
  }
}

package japi {
  /* Java API */
  trait Future[+T] { self: akka.dispatch.Future[T] ⇒
    private[japi] final def onTimeout[A >: T](proc: Procedure[akka.dispatch.Future[A]]): this.type = self.onTimeout(proc(_))
    private[japi] final def onResult[A >: T](proc: Procedure[A]): this.type = self.onResult({ case r ⇒ proc(r.asInstanceOf[A]) }: PartialFunction[T, Unit])
    private[japi] final def onException(proc: Procedure[Throwable]): this.type = self.onException({ case t: Throwable ⇒ proc(t) }: PartialFunction[Throwable, Unit])
    private[japi] final def onComplete[A >: T](proc: Procedure[akka.dispatch.Future[A]]): this.type = self.onComplete(proc(_))
    private[japi] final def map[A >: T, B](f: JFunc[A, B]): akka.dispatch.Future[B] = self.map(f(_))
    private[japi] final def flatMap[A >: T, B](f: JFunc[A, akka.dispatch.Future[B]]): akka.dispatch.Future[B] = self.flatMap(f(_))
    private[japi] final def foreach[A >: T](proc: Procedure[A]): Unit = self.foreach(proc(_))
    private[japi] final def filter[A >: T](p: JFunc[A, java.lang.Boolean]): akka.dispatch.Future[A] =
      self.filter((a: Any) ⇒ p(a.asInstanceOf[A])).asInstanceOf[akka.dispatch.Future[A]]
  }
}

object Promise {

  /**
   * Creates a non-completed, new, Promise with the supplied timeout in milliseconds
   */
  def apply[A](timeout: Timeout)(implicit dispatcher: MessageDispatcher): Promise[A] = new DefaultPromise[A](timeout)

  /**
   * Creates a non-completed, new, Promise with the default timeout (akka.actor.timeout in conf)
   */
  def apply[A]()(implicit dispatcher: MessageDispatcher): Promise[A] = apply(Timeout.default)

  /**
   * Construct a completable channel
   */
  def channel(timeout: Long = Actor.TIMEOUT)(implicit dispatcher: MessageDispatcher): ActorPromise = new ActorPromise(timeout)
}

/**
 * Essentially this is the Promise (or write-side) of a Future (read-side).
 */
trait Promise[T] extends Future[T] {
  /**
   * Completes this Future with the specified result, if not already completed.
   * @return this
   */
  def complete(value: Either[Throwable, T]): this.type

  /**
   * Completes this Future with the specified result, if not already completed.
   * @return this
   */
  final def completeWithResult(result: T): this.type = complete(Right(result))

  /**
   * Completes this Future with the specified exception, if not already completed.
   * @return this
   */
  final def completeWithException(exception: Throwable): this.type = complete(Left(exception))

  /**
   * Completes this Future with the specified other Future, when that Future is completed,
   * unless this Future has already been completed.
   * @return this.
   */
  final def completeWith(other: Future[T]): this.type = {
    other onComplete { f ⇒ complete(f.value.get) }
    this
  }

  final def <<(value: T): Future[T] @cps[Future[Any]] = shift { cont: (Future[T] ⇒ Future[Any]) ⇒ cont(complete(Right(value))) }

  final def <<(other: Future[T]): Future[T] @cps[Future[Any]] = shift { cont: (Future[T] ⇒ Future[Any]) ⇒
    val fr = new DefaultPromise[Any](this.timeout)
    this completeWith other onComplete { f ⇒
      try {
        fr completeWith cont(f)
      } catch {
        case e: Exception ⇒
          EventHandler.error(e, this, e.getMessage)
          fr completeWithException e
      }
    }
    fr
  }

  final def <<(stream: PromiseStreamOut[T]): Future[T] @cps[Future[Any]] = shift { cont: (Future[T] ⇒ Future[Any]) ⇒
    val fr = new DefaultPromise[Any](this.timeout)
    stream.dequeue(this).onComplete { f ⇒
      try {
        fr completeWith cont(f)
      } catch {
        case e: Exception ⇒
          EventHandler.error(e, this, e.getMessage)
          fr completeWithException e
      }
    }
    fr
  }

}

//Companion object to FState, just to provide a cheap, immutable default entry
private[akka] object FState {
  val empty = new FState[Nothing]()
  def apply[T](): FState[T] = empty.asInstanceOf[FState[T]]
}

/**
 * Represents the internal state of the DefaultCompletableFuture
 */
private[akka] case class FState[T](value: Option[Either[Throwable, T]] = None, listeners: List[Future[T] ⇒ Unit] = Nil)

/**
 * The default concrete Future implementation.
 */
class DefaultPromise[T](val timeout: Timeout)(implicit val dispatcher: MessageDispatcher) extends Promise[T] {
  self ⇒

  def this()(implicit dispatcher: MessageDispatcher) = this(Timeout.default)

  def this(timeout: Long)(implicit dispatcher: MessageDispatcher) = this(Timeout(timeout))

  def this(timeout: Long, timeunit: TimeUnit)(implicit dispatcher: MessageDispatcher) = this(Timeout(timeout, timeunit))

  private val _startTimeInNanos = currentTimeInNanos
  private val ref = new AtomicReference[FState[T]](FState())

  @tailrec
  private def awaitUnsafe(waitTimeNanos: Long): Boolean = {
    if (value.isEmpty && waitTimeNanos > 0) {
      val ms = NANOS.toMillis(waitTimeNanos)
      val ns = (waitTimeNanos % 1000000l).toInt //As per object.wait spec
      val start = currentTimeInNanos
      try { ref.synchronized { if (value.isEmpty) ref.wait(ms, ns) } } catch { case e: InterruptedException ⇒ }

      awaitUnsafe(waitTimeNanos - (currentTimeInNanos - start))
    } else {
      value.isDefined
    }
  }

  def await(atMost: Duration): this.type = {
    val waitNanos =
      if (timeout.duration.isFinite && atMost.isFinite)
        atMost.toNanos min timeLeft()
      else if (atMost.isFinite)
        atMost.toNanos
      else if (timeout.duration.isFinite)
        timeLeft()
      else Long.MaxValue //If both are infinite, use Long.MaxValue

    if (awaitUnsafe(waitNanos)) this
    else throw new FutureTimeoutException("Futures timed out after [" + NANOS.toMillis(waitNanos) + "] milliseconds")
  }

  def await = await(timeout.duration)

  def isExpired: Boolean = if (timeout.duration.isFinite) timeLeft() <= 0 else false

  def value: Option[Either[Throwable, T]] = ref.get.value

  def complete(value: Either[Throwable, T]): this.type = {
    val callbacks = {
      try {
        @tailrec
        def tryComplete: List[Future[T] ⇒ Unit] = {
          val cur = ref.get
          if (cur.value.isDefined) Nil
          else if ( /*cur.value.isEmpty && */ isExpired) {
            //Empty and expired, so remove listeners
            //TODO Perhaps cancel existing onTimeout listeners in the future here?
            ref.compareAndSet(cur, FState()) //Try to reset the state to the default, doesn't matter if it fails
            Nil
          } else {
            if (ref.compareAndSet(cur, FState(Option(value), Nil))) cur.listeners
            else tryComplete
          }
        }
        tryComplete
      } finally {
        ref.synchronized { ref.notifyAll() } //Notify any evil blockers
      }
    }

    if (callbacks.nonEmpty) Future.dispatchTask(() ⇒ callbacks foreach notifyCompleted)

    this
  }

  def onComplete(func: Future[T] ⇒ Unit): this.type = {
    @tailrec //Returns whether the future has already been completed or not
    def tryAddCallback(): Boolean = {
      val cur = ref.get
      if (cur.value.isDefined) true
      else if (isExpired) false
      else if (ref.compareAndSet(cur, cur.copy(listeners = func :: cur.listeners))) false
      else tryAddCallback()
    }

    if (tryAddCallback()) Future.dispatchTask(() ⇒ notifyCompleted(func))

    this
  }

  def onTimeout(func: Future[T] ⇒ Unit): this.type = {
    val runNow =
      if (!timeout.duration.isFinite) false //Not possible
      else if (value.isEmpty) {
        if (!isExpired) {
          val runnable = new Runnable {
            def run() {
              if (!isCompleted) {
                if (!isExpired) Scheduler.scheduleOnce(this, timeLeftNoinline(), NANOS)
                else func(DefaultPromise.this)
              }
            }
          }
          Scheduler.scheduleOnce(runnable, timeLeft(), NANOS)
          false
        } else true
      } else false

    if (runNow) Future.dispatchTask(() ⇒ notifyCompleted(func))

    this
  }

  final def orElse[A >: T](fallback: ⇒ A): Future[A] =
    if (timeout.duration.isFinite) {
      value match {
        case Some(_)        ⇒ this
        case _ if isExpired ⇒ Future[A](fallback)
        case _ ⇒
          val promise = new DefaultPromise[A](Timeout.never) //TODO FIXME We can't have infinite timeout here, doesn't make sense.
          promise completeWith this
          val runnable = new Runnable {
            def run() {
              if (!isCompleted) {
                if (!isExpired) Scheduler.scheduleOnce(this, timeLeftNoinline(), NANOS)
                else promise complete (try { Right(fallback) } catch { case e: Exception ⇒ Left(e) })
              }
            }
          }
          Scheduler.scheduleOnce(runnable, timeLeft(), NANOS)
          promise
      }
    } else this

  private def notifyCompleted(func: Future[T] ⇒ Unit) {
    try { func(this) } catch { case e ⇒ EventHandler notify EventHandler.Error(e, this, "Future onComplete-callback raised an exception") } //TODO catch, everything? Really?
  }

  @inline
  private def currentTimeInNanos: Long = MILLIS.toNanos(System.currentTimeMillis) //TODO Switch to math.abs(System.nanoTime)?
  //TODO: the danger of Math.abs is that it could break the ordering of time. So I would not recommend an abs.
  @inline
  private def timeLeft(): Long = timeoutInNanos - (currentTimeInNanos - _startTimeInNanos)

  private def timeLeftNoinline(): Long = timeLeft()
}

class ActorPromise(timeout: Timeout)(implicit dispatcher: MessageDispatcher) extends DefaultPromise[Any](timeout)(dispatcher) with ForwardableChannel with ExceptionChannel[Any] {

  def !(message: Any)(implicit channel: UntypedChannel) = completeWithResult(message)

  override def sendException(ex: Throwable) = {
    completeWithException(ex)
    value == Some(Left(ex))
  }

  def channel: UntypedChannel = this

}

object ActorPromise {
  def apply(f: Promise[Any]): ActorPromise =
    new ActorPromise(f.timeout)(f.dispatcher) {
      completeWith(f)
      override def !(message: Any)(implicit channel: UntypedChannel) = f completeWithResult message
      override def sendException(ex: Throwable) = {
        f completeWithException ex
        f.value == Some(Left(ex))
      }
    }
}

/**
 * An already completed Future is seeded with it's result at creation, is useful for when you are participating in
 * a Future-composition but you already have a value to contribute.
 */
sealed class KeptPromise[T](suppliedValue: Either[Throwable, T])(implicit val dispatcher: MessageDispatcher) extends Promise[T] {
  val value = Some(suppliedValue)

  def complete(value: Either[Throwable, T]): this.type = this
  def onComplete(func: Future[T] ⇒ Unit): this.type = {
    Future dispatchTask (() ⇒ func(this))
    this
  }
  def await(atMost: Duration): this.type = this
  def await: this.type = this
  def isExpired: Boolean = true
  def timeout: Timeout = Timeout.zero

  final def onTimeout(func: Future[T] ⇒ Unit): this.type = this
  final def orElse[A >: T](fallback: ⇒ A): Future[A] = this

}
