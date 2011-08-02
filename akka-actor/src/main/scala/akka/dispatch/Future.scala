/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import akka.AkkaException
import akka.event.EventHandler
import akka.actor.{ Actor, Channel, ForwardableChannel, NullChannel, UntypedChannel, ActorRef, Scheduler, Timeout }
import akka.util.{ Duration, BoxedType }
import akka.japi.{ Procedure, Function ⇒ JFunc }

import scala.util.continuations._

import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.{ ConcurrentLinkedQueue, TimeUnit, Callable }
import java.util.concurrent.TimeUnit.{ NANOSECONDS ⇒ NANOS, MILLISECONDS ⇒ MILLIS }
import java.lang.{ Iterable ⇒ JIterable }
import java.util.{ LinkedList ⇒ JLinkedList }

import scala.annotation.tailrec
import scala.collection.mutable.Stack
import akka.util.{ Switch, Duration, BoxedType }
import java.util.concurrent.atomic.{ AtomicLong, AtomicBoolean }

class FutureTimeoutException(message: String, cause: Throwable = null) extends AkkaException(message, cause)

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
   * Returns a Future to the result of the first future in the list that is completed
   */
  def firstCompletedOf[T](futures: Iterable[Future[T]], timeout: Timeout = Timeout.never): Future[T] = {
    val futureResult = new DefaultPromise[T](timeout)

    val completeFirst: Future[T] ⇒ Unit = _.value.foreach(futureResult complete _)
    for (f ← futures) f onComplete completeFirst

    futureResult
  }

  /**
   * Java API.
   * Returns a Future to the result of the first future in the list that is completed
   */
  def firstCompletedOf[T <: AnyRef](futures: java.lang.Iterable[Future[T]], timeout: Timeout): Future[T] =
    firstCompletedOf(scala.collection.JavaConversions.iterableAsScalaIterable(futures), timeout)

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
   * Java API
   * A non-blocking fold over the specified futures.
   * The fold is performed on the thread where the last future is completed,
   * the result will be the first failure of any of the futures, or any failure in the actual fold,
   * or the result of the fold.
   */
  def fold[T <: AnyRef, R <: AnyRef](zero: R, timeout: Timeout, futures: java.lang.Iterable[Future[T]], fun: akka.japi.Function2[R, T, R]): Future[R] =
    fold(zero, timeout)(scala.collection.JavaConversions.iterableAsScalaIterable(futures))(fun.apply _)

  def fold[T <: AnyRef, R <: AnyRef](zero: R, timeout: Long, futures: java.lang.Iterable[Future[T]], fun: akka.japi.Function2[R, T, R]): Future[R] = fold(zero, timeout: Timeout, futures, fun)

  def fold[T <: AnyRef, R <: AnyRef](zero: R, futures: java.lang.Iterable[Future[T]], fun: akka.japi.Function2[R, T, R]): Future[R] = fold(zero, Timeout.default, futures, fun)

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
   * Java API.
   * Initiates a fold over the supplied futures where the fold-zero is the result value of the Future that's completed first
   */
  def reduce[T <: AnyRef, R >: T](futures: java.lang.Iterable[Future[T]], timeout: Timeout, fun: akka.japi.Function2[R, T, T]): Future[R] =
    reduce(scala.collection.JavaConversions.iterableAsScalaIterable(futures), timeout)(fun.apply _)

  def reduce[T <: AnyRef, R >: T](futures: java.lang.Iterable[Future[T]], timeout: Long, fun: akka.japi.Function2[R, T, T]): Future[R] = reduce(futures, timeout: Timeout, fun)

  /**
   * Java API.
   * Simple version of Futures.traverse. Transforms a java.lang.Iterable[Future[A]] into a Future[java.lang.Iterable[A]].
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
   * Transforms a java.lang.Iterable[A] into a Future[java.lang.Iterable[B]] using the provided Function A => Future[B].
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
   * Transforms a java.lang.Iterable[A] into a Future[java.lang.Iterable[B]] using the provided Function A => Future[B].
   * This is useful for performing a parallel map. For example, to apply a function to all items of a list
   * in parallel.
   *
   * def traverse[A, B, M[_] <: Traversable[_]](in: M[A], timeout: Long = Actor.TIMEOUT)(fn: A => Future[B])(implicit cbf: CanBuildFrom[M[A], B, M[B]]): Future[M[B]] =
   * in.foldLeft(new DefaultPromise[Builder[B, M[B]]](timeout).completeWithResult(cbf(in)): Future[Builder[B, M[B]]]) { (fr, a) =>
   * val fb = fn(a.asInstanceOf[A])
   * for (r <- fr; b <-fb) yield (r += b)
   * }.map(_.result)
   */
  def traverse[A, B](in: JIterable[A], fn: JFunc[A, Future[B]]): Future[JIterable[B]] = traverse(in, Timeout.default, fn)
}

object Future {

  /**
   * This method constructs and returns a Future that will eventually hold the result of the execution of the supplied body
   * The execution is performed by the specified Dispatcher.
   */
  def apply[T](body: ⇒ T)(implicit dispatcher: MessageDispatcher, timeout: Timeout = implicitly): Future[T] =
    dispatcher.dispatchFuture(() ⇒ body, timeout)

  def apply[T](body: ⇒ T, timeout: Timeout)(implicit dispatcher: MessageDispatcher): Future[T] =
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
   * Transforms a Traversable[A] into a Future[Traversable[B]] using the provided Function A => Future[B].
   * This is useful for performing a parallel map. For example, to apply a function to all items of a list
   * in parallel:
   * <pre>
   * val myFutureList = Futures.traverse(myList)(x => Future(myFunc(x)))
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
  def flow[A](body: ⇒ A @cps[Future[Any]])(implicit timeout: Timeout): Future[A] = {
    val future = Promise[A](timeout)
    (reset(future.asInstanceOf[Promise[Any]].completeWithResult(body)): Future[Any]) onException { case e ⇒ future completeWithException e }
    future
  }
}

sealed trait Future[+T] {

  /**
   * For use only within a Future.flow block or another compatible Delimited Continuations reset block.
   *
   * Returns the result of this Future without blocking, by suspending execution and storing it as a
   * continuation until the result is available.
   *
   * If this Future is untyped (a Future[Nothing]), a type parameter must be explicitly provided or
   * execution will fail. The normal result of getting a Future from an ActorRef using ? will return
   * an untyped Future.
   */
  def apply[A >: T](): A @cps[Future[Any]] = shift(this flatMap (_: A ⇒ Future[Any]))

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
   * timeout has expired. The timeout will be the least value of 'atMost' and the timeout
   * supplied at the constructuion of this Future.
   * In the case of the timeout expiring a FutureTimeoutException will be thrown.
   */
  def await(atMost: Duration): Future[T]

  /**
   * Await completion of this Future (as `await`) and return its value if it
   * conforms to A's erased type.
   *
   * def as[A](implicit m: Manifest[A]): Option[A] =
   * try {
   * await
   * value match {
   * case None                ⇒ None
   * case Some(_: Left[_, _]) ⇒ None
   * case Some(Right(v))      ⇒ Some(BoxedType(m.erasure).cast(v).asInstanceOf[A])
   * }
   * } catch {
   * case _: Exception ⇒ None
   * }
   */

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
  final def result: Option[T] = {
    val v = value
    if (v.isDefined) v.get.right.toOption
    else None
  }

  /**
   * Returns the contained exception of this Future if it exists.
   */
  final def exception: Option[Throwable] = {
    val v = value
    if (v.isDefined) v.get.left.toOption
    else None
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
   *   val result = future onResult {
   *     case Foo => "foo"
   *     case Bar => "bar"
   *   }
   * </pre>
   */
  final def onResult(pf: PartialFunction[Any, Unit]): this.type = onComplete { f ⇒
    val optr = f.result
    if (optr.isDefined) {
      val r = optr.get
      if (pf isDefinedAt r) pf(r)
    }
  }

  /**
   * When the future is completed with an exception, apply the provided
   * PartialFunction to the exception.
   * <pre>
   *   val result = future onException {
   *     case Foo => "foo"
   *     case Bar => "bar"
   *   }
   * </pre>
   */
  final def onException(pf: PartialFunction[Throwable, Unit]): Future[T] = onComplete { f ⇒
    val opte = f.exception
    if (opte.isDefined) {
      val e = opte.get
      if (pf isDefinedAt e) pf(e)
    }
  }

  def onTimeout(func: Future[T] ⇒ Unit): this.type

  def orElse[A >: T](fallback: ⇒ A): Future[A]

  /**
   * Creates a new Future by applying a PartialFunction to the successful
   * result of this Future if a match is found, or else return a MatchError.
   * If this Future is completed with an exception then the new Future will
   * also contain this exception.
   * Example:
   * <pre>
   * val future1 = for {
   *   a <- actor ? Req("Hello") collect { case Res(x: Int)    => x }
   *   b <- actor ? Req(a)       collect { case Res(x: String) => x }
   *   c <- actor ? Req(7)       collect { case Res(x: String) => x }
   * } yield b + "-" + c
   * </pre>
   */
  final def collect[A](pf: PartialFunction[Any, A])(implicit timeout: Timeout): Future[A] = value match {
    case Some(Right(r)) ⇒
      new KeptPromise[A](try {
        if (pf isDefinedAt r)
          Right(pf(r))
        else
          Left(new MatchError(r))
      } catch {
        case e: Exception ⇒
          EventHandler.error(e, this, e.getMessage)
          Left(e)
      })
    case Some(_) ⇒
      this.asInstanceOf[Future[A]]
    case None ⇒
      val future = new DefaultPromise[A](timeout)
      onComplete { self ⇒
        future complete {
          self.value.get match {
            case Right(r) ⇒
              try {
                if (pf isDefinedAt r) Right(pf(r))
                else Left(new MatchError(r))
              } catch {
                case e: Exception ⇒
                  EventHandler.error(e, this, e.getMessage)
                  Left(e)
              }
            case v ⇒ v.asInstanceOf[Either[Throwable, A]]
          }
        }
      }
      future
  }

  /**
   * Creates a new Future that will handle any matching Throwable that this
   * Future might contain. If there is no match, or if this Future contains
   * a valid result then the new Future will contain the same.
   * Example:
   * <pre>
   * Future(6 / 0) recover { case e: ArithmeticException => 0 } // result: 0
   * Future(6 / 0) recover { case e: NotFoundException   => 0 } // result: exception
   * Future(6 / 2) recover { case e: ArithmeticException => 0 } // result: 3
   * </pre>
   */
  final def recover[A >: T](pf: PartialFunction[Throwable, A])(implicit timeout: Timeout): Future[A] = value match {
    case Some(Left(e)) ⇒
      try {
        if (pf isDefinedAt e)
          new KeptPromise(Right(pf(e)))
        else
          this.asInstanceOf[Future[A]]
      } catch {
        case e: Exception ⇒
          EventHandler.error(e, this, e.getMessage)
          new KeptPromise(Left(e))
      }
    case Some(_) ⇒
      this.asInstanceOf[Future[A]]
    case None ⇒
      val future = new DefaultPromise[A](timeout)
      onComplete { self ⇒
        future complete {
          self.value.get match {
            case Left(e) ⇒
              try {
                if (pf isDefinedAt e) Right(pf(e))
                else Left(e)
              } catch {
                case x: Exception ⇒
                  Left(x)
              }
            case v ⇒ v
          }
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
  final def map[A](f: T ⇒ A)(implicit timeout: Timeout): Future[A] = value match {
    case Some(Right(r)) ⇒
      new KeptPromise[A](try {
        Right(f(r))
      } catch {
        case e: Exception ⇒
          EventHandler.error(e, this, e.getMessage)
          Left(e)
      })
    case Some(_) ⇒
      this.asInstanceOf[Future[A]]
    case None ⇒
      val future = new DefaultPromise[A](timeout)
      onComplete { self ⇒
        future complete {
          self.value.get match {
            case Right(r) ⇒
              try {
                Right(f(r))
              } catch {
                case e: Exception ⇒
                  EventHandler.error(e, this, e.getMessage)
                  Left(e)
              }
            case v ⇒ v.asInstanceOf[Either[Throwable, A]]
          }
        }
      }
      future
  }

  /**
   * Creates a new Future[A] which is completed with this Future's result if
   * that conforms to A's erased type or a ClassCastException otherwise.
   */
  final def mapTo[A](implicit m: Manifest[A], timeout: Timeout = this.timeout): Future[A] = value match {
    case Some(Right(t)) ⇒
      new KeptPromise(try {
        Right(BoxedType(m.erasure).cast(t).asInstanceOf[A])
      } catch {
        case e: ClassCastException ⇒ Left(e)
      })
    case Some(_) ⇒
      this.asInstanceOf[Future[A]]
    case None ⇒
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
  final def flatMap[A](f: T ⇒ Future[A])(implicit timeout: Timeout): Future[A] = value match {
    case Some(Right(r)) ⇒
      try {
        f(r)
      } catch {
        case e: Exception ⇒
          EventHandler.error(e, this, e.getMessage)
          new KeptPromise(Left(e))
      }
    case Some(_) ⇒
      this.asInstanceOf[Future[A]]
    case None ⇒
      val future = new DefaultPromise[A](timeout)
      onComplete {
        _.value.get match {
          case Right(r) ⇒
            try {
              future completeWith f(r)
            } catch {
              case e: Exception ⇒
                EventHandler.error(e, this, e.getMessage)
                future complete Left(e)
            }
          case v ⇒ future complete v.asInstanceOf[Either[Throwable, A]]
        }
      }
      future
  }

  final def foreach(f: T ⇒ Unit): Unit = onComplete {
    _.result match {
      case Some(v) ⇒ f(v)
      case None    ⇒
    }
  }

  final def withFilter(p: T ⇒ Boolean) = new FutureWithFilter[T](this, p)

  final class FutureWithFilter[+A](self: Future[A], p: A ⇒ Boolean)(implicit timeout: Timeout) {
    def foreach(f: A ⇒ Unit): Unit = self filter p foreach f
    def map[B](f: A ⇒ B): Future[B] = self filter p map f
    def flatMap[B](f: A ⇒ Future[B]): Future[B] = self filter p flatMap f
    def withFilter(q: A ⇒ Boolean): FutureWithFilter[A] = new FutureWithFilter[A](self, x ⇒ p(x) && q(x))
  }

  final def filter(p: T ⇒ Boolean)(implicit timeout: Timeout): Future[T] = value match {
    case Some(Right(r)) ⇒
      try {
        if (p(r))
          this
        else
          new KeptPromise(Left(new MatchError(r)))
      } catch {
        case e: Exception ⇒
          EventHandler.error(e, this, e.getMessage)
          new KeptPromise(Left(e))
      }
    case Some(_) ⇒
      this
    case None ⇒
      val future = new DefaultPromise[T](timeout)
      onComplete { self ⇒
        future complete {
          self.value.get match {
            case Right(r) ⇒
              try {
                if (p(r))
                  Right(r)
                else
                  Left(new MatchError(r))
              } catch {
                case e: Exception ⇒
                  EventHandler.error(e, this, e.getMessage)
                  Left(e)
              }
            case v ⇒ v
          }
        }
      }
      future
  }

  /**
   * Returns the current result, throws the exception if one has been raised, else returns None
   */
  final def resultOrException: Option[T] = {
    val v = value
    if (v.isDefined) {
      val r = v.get
      if (r.isLeft) throw r.left.get
      else r.right.toOption
    } else None
  }

  /* Java API */
  final def onComplete[A >: T](proc: Procedure[Future[A]]): this.type = onComplete(proc(_))

  final def map[A >: T, B](f: JFunc[A, B]): Future[B] = map(f(_))

  final def flatMap[A >: T, B](f: JFunc[A, Future[B]]): Future[B] = flatMap(f(_))

  final def foreach[A >: T](proc: Procedure[A]): Unit = foreach(proc(_))

  final def filter(p: JFunc[Any, Boolean]): Future[Any] = filter(p(_))

}

object Promise {

  /**
   * Creates a non-completed, new, Promise with the supplied timeout in milliseconds
   */
  def apply[A](timeout: Timeout): Promise[A] = new DefaultPromise[A](timeout)

  /**
   * Creates a non-completed, new, Promise with the default timeout (akka.actor.timeout in conf)
   */
  def apply[A](): Promise[A] = apply(Timeout.default)

  /**
   * Construct a completable channel
   */
  def channel(timeout: Long = Actor.TIMEOUT): ActorPromise = new ActorPromise(timeout)

  private[akka] val callbacksPendingExecution = new ThreadLocal[Option[Stack[() ⇒ Unit]]]() {
    override def initialValue = None
  }
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
    val fr = new DefaultPromise[Any]()
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
    val fr = Promise[Any]()
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

/**
 * The default concrete Future implementation.
 */
class DefaultPromise[T](val timeout: Timeout) extends Promise[T] {
  self ⇒

  def this() = this(Timeout.default)

  def this(timeout: Long) = this(Timeout(timeout))

  def this(timeout: Long, timeunit: TimeUnit) = this(Timeout(timeout, timeunit))

  private val _startTimeInNanos = currentTimeInNanos
  private val _lock = new ReentrantLock
  private val _signal = _lock.newCondition
  private var _value: Option[Either[Throwable, T]] = None
  private var _listeners: List[Future[T] ⇒ Unit] = Nil

  /**
   * Must be called inside _lock.lock<->_lock.unlock
   * Returns true if completed within the timeout
   */
  @tailrec
  private def awaitUnsafe(waitTimeNanos: Long): Boolean = {
    if (_value.isEmpty && waitTimeNanos > 0) {
      val start = currentTimeInNanos
      val remainingNanos = try {
        _signal.awaitNanos(waitTimeNanos)
      } catch {
        case e: InterruptedException ⇒
          waitTimeNanos - (currentTimeInNanos - start)
      }
      awaitUnsafe(remainingNanos)
    } else {
      _value.isDefined
    }
  }

  def await(atMost: Duration) = {
    _lock.lock()
    try {
      if (!atMost.isFinite && !timeout.duration.isFinite) { //If wait until infinity
        while (_value.isEmpty) { _signal.await }
        this
      } else { //Limited wait
        val time = if (!atMost.isFinite) timeLeft() //If atMost is infinity, use preset timeout
        else if (!timeout.duration.isFinite) atMost.toNanos //If preset timeout is infinite, use atMost
        else atMost.toNanos min timeLeft() //Otherwise use the smallest of them
        if (awaitUnsafe(time)) this
        else throw new FutureTimeoutException("Future timed out after [" + NANOS.toMillis(time) + "] ms")
      }
    } finally { _lock.unlock }
  }

  def await = await(timeout.duration)

  def isExpired: Boolean = if (timeout.duration.isFinite) timeLeft() <= 0 else false

  def value: Option[Either[Throwable, T]] = {
    _lock.lock
    try {
      _value
    } finally {
      _lock.unlock
    }
  }

  def complete(value: Either[Throwable, T]): this.type = {
    _lock.lock
    val notifyTheseListeners = try {
      if (_value.isEmpty) { //Only complete if we aren't expired
        if (!isExpired) {
          _value = Some(value)
          val existingListeners = _listeners
          _listeners = Nil
          existingListeners
        } else {
          _listeners = Nil
          Nil
        }
      } else Nil
    } finally {
      _signal.signalAll
      _lock.unlock
    }

    if (notifyTheseListeners.nonEmpty) { // Steps to ensure we don't run into a stack-overflow situation
      @tailrec
      def runCallbacks(rest: List[Future[T] ⇒ Unit], callbacks: Stack[() ⇒ Unit]) {
        if (rest.nonEmpty) {
          notifyCompleted(rest.head)
          while (callbacks.nonEmpty) { callbacks.pop().apply() }
          runCallbacks(rest.tail, callbacks)
        }
      }

      val pending = Promise.callbacksPendingExecution.get
      if (pending.isDefined) { //Instead of nesting the calls to the callbacks (leading to stack overflow)
        pending.get.push(() ⇒ { // Linearize/aggregate callbacks at top level and then execute
          val doNotify = notifyCompleted _ //Hoist closure to avoid garbage
          notifyTheseListeners foreach doNotify
        })
      } else {
        try {
          val callbacks = Stack[() ⇒ Unit]() // Allocate new aggregator for pending callbacks
          Promise.callbacksPendingExecution.set(Some(callbacks)) // Specify the callback aggregator
          runCallbacks(notifyTheseListeners, callbacks) // Execute callbacks, if they trigger new callbacks, they are aggregated
        } finally { Promise.callbacksPendingExecution.set(None) } // Ensure cleanup
      }
    }

    this
  }

  def onComplete(func: Future[T] ⇒ Unit): this.type = {
    _lock.lock
    val notifyNow = try {
      if (_value.isEmpty) {
        if (!isExpired) { //Only add the listener if the future isn't expired
          _listeners ::= func
          false
        } else false //Will never run the callback since the future is expired
      } else true
    } finally {
      _lock.unlock
    }

    if (notifyNow) notifyCompleted(func)

    this
  }

  def onTimeout(func: Future[T] ⇒ Unit): this.type = {
    if (timeout.duration.isFinite) {
      _lock.lock
      val runNow = try {
        if (_value.isEmpty) {
          if (!isExpired) {
            val runnable = new Runnable {
              def run() {
                if (!isCompleted) func(self)
              }
            }
            Scheduler.scheduleOnce(runnable, timeLeft, NANOS)
            false
          } else true
        } else false
      } finally {
        _lock.unlock
      }

      if (runNow) func(this)
    }

    this
  }

  final def orElse[A >: T](fallback: ⇒ A): Future[A] =
    if (timeout.duration.isFinite) {
      value match {
        case Some(_)        ⇒ this
        case _ if isExpired ⇒ new KeptPromise[A](try { Right(fallback) } catch { case e: Exception ⇒ Left(e) })
        case _ ⇒
          val promise = new DefaultPromise[A](Timeout.never)
          promise completeWith this
          val runnable = new Runnable {
            def run() {
              if (!isCompleted) promise complete (try { Right(fallback) } catch { case e: Exception ⇒ Left(e) })
            }
          }
          Scheduler.scheduleOnce(runnable, timeLeft, NANOS)
          promise
      }
    } else this

  private def notifyCompleted(func: Future[T] ⇒ Unit) {
    try {
      func(this)
    } catch {
      case e ⇒ EventHandler.error(e, this, "Future onComplete-callback raised an exception")
    }
  }

  @inline
  private def currentTimeInNanos: Long = MILLIS.toNanos(System.currentTimeMillis)
  @inline
  private def timeLeft(): Long = timeoutInNanos - (currentTimeInNanos - _startTimeInNanos)
}

class ActorPromise(timeout: Timeout) extends DefaultPromise[Any](timeout) with ForwardableChannel {

  def !(message: Any)(implicit channel: UntypedChannel = NullChannel) = completeWithResult(message)

  def sendException(ex: Throwable) = completeWithException(ex)

  def channel: UntypedChannel = this

  def isUsableOnlyOnce = true
  def isUsable = !isCompleted
  def isReplyable = false
  def canSendException = true

  @deprecated("ActorPromise merged with Channel[Any], just use 'this'", "1.2")
  def future = this
}

object ActorPromise {
  def apply(f: Promise[Any]): ActorPromise =
    new ActorPromise(f.timeout) {
      completeWith(f)
      override def !(message: Any)(implicit channel: UntypedChannel) = f completeWithResult message
      override def sendException(ex: Throwable) = f completeWithException ex
    }
}

/**
 * An already completed Future is seeded with it's result at creation, is useful for when you are participating in
 * a Future-composition but you already have a value to contribute.
 */
sealed class KeptPromise[T](suppliedValue: Either[Throwable, T]) extends Promise[T] {
  val value = Some(suppliedValue)

  def complete(value: Either[Throwable, T]): this.type = this
  def onComplete(func: Future[T] ⇒ Unit): this.type = { func(this); this }
  def await(atMost: Duration): this.type = this
  def await: this.type = this
  def isExpired: Boolean = true
  def timeout: Timeout = Timeout.zero

  final def onTimeout(func: Future[T] ⇒ Unit): this.type = this
  final def orElse[A >: T](fallback: ⇒ A): Future[A] = this

}
