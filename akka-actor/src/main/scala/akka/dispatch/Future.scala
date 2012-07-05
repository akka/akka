/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.dispatch

import akka.AkkaException
import akka.event.EventHandler
import akka.actor.{ Actor, Channel, ForwardableChannel, NullChannel, UntypedChannel, ActorRef, Scheduler, ExceptionChannel }
import akka.japi.{ Procedure, Function ⇒ JFunc }

import scala.util.continuations._
import scala.collection.JavaConversions

import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.{ ConcurrentLinkedQueue, TimeUnit, Callable }
import java.util.concurrent.TimeUnit.{ NANOSECONDS ⇒ NANOS, MILLISECONDS ⇒ MILLIS }
import java.lang.{ Iterable ⇒ JIterable }
import java.util.{ LinkedList ⇒ JLinkedList }
import scala.collection.mutable.Stack
import annotation.tailrec
import akka.util.{ Switch, Duration, BoxedType }
import java.util.concurrent.atomic.{AtomicReference, AtomicBoolean}
import scala.Math

class FutureTimeoutException(message: String, cause: Throwable = null) extends AkkaException(message, cause) {
  def this(message: String) = this(message, null)
}

class FutureCanceledException extends AkkaException

object Futures {

  /**
   * Java API, equivalent to Future.apply
   */
  def future[T](body: Callable[T]): Future[T] =
    Future(body.call)

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
  def future[T](body: Callable[T], timeout: Long, dispatcher: MessageDispatcher): Future[T] =
    Future(body.call, timeout)(dispatcher)

  /**
   * Returns a Future to the result of the first future in the list that is completed
   */
  def firstCompletedOf[T](futures: Iterable[Future[T]], timeout: Long = Long.MaxValue): Future[T] = {
    val futureResult = new CompositePromise[T, T](futures, timeout)

    val completeFirst: Future[T] ⇒ Unit = _.value.foreach(futureResult complete _)
    for (f ← futures) f onComplete completeFirst

    futureResult
  }

  /**
   * Java API.
   * Returns a Future to the result of the first future in the list that is completed
   */
  def firstCompletedOf[T <: AnyRef](futures: java.lang.Iterable[Future[T]], timeout: Long): Future[T] =
    firstCompletedOf(JavaConversions.iterableAsScalaIterable(futures), timeout)

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
  def fold[T, R](zero: R, timeout: Long = Actor.TIMEOUT)(futures: Iterable[Future[T]])(foldFun: (R, T) ⇒ R): Future[R] = {
    if (futures.isEmpty) {
      new AlreadyCompletedFuture[R](Right(zero))
    } else {
      val result = new CompositePromise[T, R](futures, timeout)
      val results = new ConcurrentLinkedQueue[T]()
      val done = new Switch(false)
      val allDone = futures.size

      val aggregate: Future[T] ⇒ Unit = f ⇒ if (done.isOff && !result.isCompleted) { //TODO: This is an optimization, is it premature?
        f.value.get match {
          case r: Right[Throwable, T] ⇒
            val added = results add r.b
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
                }
                finally {
                  results.clear
                }
              }
            }
          case l: Left[Throwable, T] ⇒
            if (done.switchOn) {
              result completeWithException l.a
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
  def fold[T <: AnyRef, R <: AnyRef](zero: R, timeout: Long, futures: java.lang.Iterable[Future[T]], fun: akka.japi.Function2[R, T, R]): Future[R] =
    fold(zero, timeout)(JavaConversions.iterableAsScalaIterable(futures))(fun.apply _)

  /**
   * Initiates a fold over the supplied futures where the fold-zero is the result value of the Future that's completed first
   * Example:
   * <pre>
   *   val result = Futures.reduce(futures)(_ + _).await.result
   * </pre>
   */
  def reduce[T, R >: T](futures: Iterable[Future[T]], timeout: Long = Actor.TIMEOUT)(op: (R, T) ⇒ T): Future[R] = {
    if (futures.isEmpty)
      new AlreadyCompletedFuture[R](Left(new UnsupportedOperationException("empty reduce left")))
    else {
      val result = new CompositePromise[T, R](futures, timeout)
      val seedFound = new AtomicBoolean(false)
      val seedFold: Future[T] ⇒ Unit = f ⇒ {
        if (seedFound.compareAndSet(false, true)) { //Only the first completed should trigger the fold
          f.value.get match {
            case r: Right[Throwable, T] ⇒
              result.completeWith(fold(r.b, timeout)(futures.filterNot(_ eq f))(op))
            case l: Left[Throwable, T] ⇒
              result.completeWithException(l.a)
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
  def reduce[T <: AnyRef, R >: T](futures: java.lang.Iterable[Future[T]], timeout: Long, fun: akka.japi.Function2[R, T, T]): Future[R] =
    reduce(JavaConversions.iterableAsScalaIterable(futures), timeout)(fun.apply _)

  /**
   * Java API.
   * Simple version of Futures.traverse. Transforms a java.lang.Iterable[Future[A]] into a Future[java.lang.Iterable[A]].
   * Useful for reducing many Futures into a single Future.
   */
  def sequence[A](in: JIterable[Future[A]], timeout: Long): Future[JIterable[A]] = {
    val it = JavaConversions.iterableAsScalaIterable(in)
    val f = it.foldLeft(Future(new JLinkedList[A]()))((fr, fa) ⇒
      for (r ← fr; a ← fa) yield {
        r add a
        r
      })
    val result = new CompositePromise[A, JIterable[A]](it, timeout)
    result completeWith f
  }

  /**
   * Java API.
   * Simple version of Futures.traverse. Transforms a java.lang.Iterable[Future[A]] into a Future[java.lang.Iterable[A]].
   * Useful for reducing many Futures into a single Future.
   */
  def sequence[A](in: JIterable[Future[A]]): Future[JIterable[A]] = sequence(in, Actor.TIMEOUT)

  /**
   * Java API.
   * Transforms a java.lang.Iterable[A] into a Future[java.lang.Iterable[B]] using the provided Function A ⇒ Future[B].
   * This is useful for performing a parallel map. For example, to apply a function to all items of a list
   * in parallel.
   */
  def traverse[A, B](in: JIterable[A], timeout: Long, fn: JFunc[A, Future[B]]): Future[JIterable[B]] =
    JavaConversions.iterableAsScalaIterable(in).foldLeft(Future(new JLinkedList[B]())) { (fr, a) ⇒
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
  def traverse[A, B](in: JIterable[A], fn: JFunc[A, Future[B]]): Future[JIterable[B]] = traverse(in, Actor.TIMEOUT, fn)

  // =====================================
  // Deprecations
  // =====================================

  /**
   * (Blocking!)
   */
  @deprecated("Will be removed after 1.1, if you must block, use: futures.foreach(_.await)", "1.1")
  def awaitAll(futures: List[Future[_]]): Unit = futures.foreach(_.await)

  /**
   *  Returns the First Future that is completed (blocking!)
   */
  @deprecated("Will be removed after 1.1, if you must block, use: firstCompletedOf(futures).await", "1.1")
  def awaitOne(futures: List[Future[_]], timeout: Long = Long.MaxValue): Future[_] = firstCompletedOf[Any](futures, timeout).await

  /**
   * Applies the supplied function to the specified collection of Futures after awaiting each future to be completed
   */
  @deprecated("Will be removed after 1.1, if you must block, use: futures map { f ⇒ fun(f.await) }", "1.1")
  def awaitMap[A, B](in: Traversable[Future[A]])(fun: (Future[A]) ⇒ B): Traversable[B] =
    in map { f ⇒ fun(f.await) }

  /**
   * Returns Future.resultOrException of the first completed of the 2 Futures provided (blocking!)
   */
  @deprecated("Will be removed after 1.1, if you must block, use: firstCompletedOf(List(f1,f2)).await.resultOrException", "1.1")
  def awaitEither[T](f1: Future[T], f2: Future[T]): Option[T] = firstCompletedOf[T](List(f1, f2)).await.resultOrException
}

object Future {
  /**
   * This method constructs and returns a Future that will eventually hold the result of the execution of the supplied body
   * The execution is performed by the specified Dispatcher.
   */
  def apply[T](body: ⇒ T, timeout: Long = Actor.TIMEOUT)(implicit dispatcher: MessageDispatcher): Future[T] = {
    val promise = new DefaultCompletableFuture[T](timeout)
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

  /**
   * Construct a completable channel
   */
  def channel(timeout: Long = Actor.TIMEOUT)(implicit dispatcher: MessageDispatcher) = new ActorCompletableFuture(timeout)

  /**
   * Create an empty Future with default timeout
   */
  def empty[T](timeout: Long = Actor.TIMEOUT)(implicit dispatcher: MessageDispatcher) = new DefaultCompletableFuture[T](timeout)

  import scala.collection.mutable.Builder
  import scala.collection.generic.CanBuildFrom

  /**
   * Simple version of Futures.traverse. Transforms a Traversable[Future[A]] into a Future[Traversable[A]].
   * Useful for reducing many Futures into a single Future.
   */
  def sequence[A, M[_] <: Traversable[_]](in: M[Future[A]], timeout: Long = Actor.TIMEOUT)(implicit cbf: CanBuildFrom[M[Future[A]], A, M[A]]): Future[M[A]] = {
    val vf = in.toIndexedSeq.asInstanceOf[Iterable[Future[A]]]
    val fb = vf.foldLeft(new DefaultCompletableFuture[Builder[A, M[A]]](timeout).completeWithResult(cbf(in)): Future[Builder[A, M[A]]])((fr, fa) ⇒ for (r ← fr; a ← fa.asInstanceOf[Future[A]]) yield (r += a))
    val result = new CompositePromise[A, M[A]](vf, timeout)
    fb onComplete { _.value.get match {
        case l: Left[_, _]  ⇒ result.complete(l.asInstanceOf[Either[Throwable, M[A]]])
        case Right(builder) ⇒ result.complete(Right(builder.result))
      }
    }
    result
  }

  /**
   * Transforms a Traversable[A] into a Future[Traversable[B]] using the provided Function A ⇒ Future[B].
   * This is useful for performing a parallel map. For example, to apply a function to all items of a list
   * in parallel:
   * <pre>
   * val myFutureList = Futures.traverse(myList)(x ⇒ Future(myFunc(x)))
   * </pre>
   */
  def traverse[A, B, M[_] <: Traversable[_]](in: M[A], timeout: Long = Actor.TIMEOUT)(fn: A ⇒ Future[B])(implicit cbf: CanBuildFrom[M[A], B, M[B]]): Future[M[B]] =
    in.foldLeft(new DefaultCompletableFuture[Builder[B, M[B]]](timeout).completeWithResult(cbf(in)): Future[Builder[B, M[B]]]) { (fr, a) ⇒
      val fb = fn(a.asInstanceOf[A])
      for (r ← fr; b ← fb) yield (r += b)
    }.map(_.result)

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
   * Completing a Future using 'CompletableFuture << Future' will also suspend execution until the
   * value of the other Future is available.
   *
   * The Delimited Continuations compiler plugin must be enabled in order to use this method.
   */
  def flow[A](body: ⇒ A @cps[Future[Any]], timeout: Long = Actor.TIMEOUT)(implicit dispatcher: MessageDispatcher): Future[A] = {
    val future = Promise[A](timeout)
    dispatchTask({ () ⇒
      (reify(body) foreachFull (future completeWithResult, future completeWithException): Future[Any]) onException {
        case e: Exception ⇒ future completeWithException e
      }
    }, true)
    future
  }

  /**
   * Assures that any Future tasks initiated in the current thread will be
   * executed asynchronously, including any tasks currently queued to be
   * executed in the current thread. This is needed if the current task may
   * block, causing delays in executing the remaining tasks which in some
   * cases may cause a deadlock.
   *
   * Note: Calling 'Future.await' will automatically trigger this method.
   *
   * For example, in the following block of code the call to 'latch.open'
   * might not be executed until after the call to 'latch.await', causing
   * a deadlock. By adding 'Future.blocking()' the call to 'latch.open'
   * will instead be dispatched separately from the current block, allowing
   * it to be run in parallel:
   * <pre>
   * val latch = new StandardLatch
   * val future = Future() map { _ ⇒
   *   Future.blocking()
   *   val nested = Future()
   *   nested foreach (_ ⇒ latch.open)
   *   latch.await
   * }
   * </pre>
   */
  def blocking()(implicit dispatcher: MessageDispatcher): Unit =
    _taskStack.get match {
      case Some(taskStack) if taskStack.nonEmpty ⇒
        val tasks = taskStack.elems
        taskStack.clear()
        _taskStack set None
        dispatchTask(() ⇒ _taskStack.get.get.elems = tasks, true)
      case Some(_) ⇒ _taskStack set None
      case _ => // already None
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
                case e ⇒ EventHandler notify EventHandler.Error(e, this)
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
        catch { case _: ClassCastException => None }
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

  /**
   * This Future's timeout in nanoseconds.
   */
  def timeoutInNanos: Long

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
    case Some(r) ⇒ r.right.toOption
    case _ ⇒ None
  }

  /**
   * Returns the contained exception of this Future if it exists.
   */
  final def exception: Option[Throwable] = value match {
    case Some(r) ⇒ r.left.toOption
    case _ ⇒ None
  }

  /**
   * When this Future is completed, apply the provided function to the
   * Future. If the Future has already been completed, this will apply
   * immediately. Will not be called in case of a timeout, which also holds if
   * corresponding Promise is attempted to complete after expiry. Multiple
   * callbacks may be registered; there is no guarantee that they will be
   * executed in a particular order.
   */
  def onComplete(func: Future[T] ⇒ Unit): this.type

  /**
   * When the future is completed with a valid result, apply the provided
   * PartialFunction to the result. See `onComplete` for more details.
   * <pre>
   *   future receive {
   *     case Foo ⇒ target ! "foo"
   *     case Bar ⇒ target ! "bar"
   *   }
   * </pre>
   */
  @deprecated("Use `onResult` instead, will be removed in the future", "1.2")
  final def receive(pf: PartialFunction[Any, Unit]): this.type = onResult(pf)

  /**
   * When the future is completed with a valid result, apply the provided
   * PartialFunction to the result. See `onComplete` for more details.
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
      case _ ⇒
    }
  }

  /**
   * When the future is completed with an exception, apply the provided
   * PartialFunction to the exception. See `onComplete` for more details.
   * <pre>
   *   future onException {
   *     case NumberFormatException ⇒ target ! "wrong format"
   *   }
   * </pre>
   */
  final def onException(pf: PartialFunction[Throwable, Unit]): this.type = onComplete {
    _.value match {
      case Some(Left(ex)) if pf isDefinedAt ex ⇒ pf(ex)
      case _ ⇒
    }
  }

  /**
   * Registers a function that will be executed when this Future times out.
   * <pre>
   *    future onTimeout {
   *      f => doSomethingOnTimeout(f)
   *    }
   * </pre>
   */
  def onTimeout(func: Future[T] ⇒ Unit): this.type

  /**
   * Creates a new Future by applying a PartialFunction to the successful
   * result of this Future if a match is found, or else return a MatchError.
   * If this Future is completed with an exception then the new Future will
   * also contain this exception.
   * Example:
   * <pre>
   * val future1 = for {
   *   a <- actor ? Req("Hello") collect { case Res(x: Int)    ⇒ x }
   *   b <- actor ? Req(a)       collect { case Res(x: String) ⇒ x }
   *   c <- actor ? Req(7)       collect { case Res(x: String) ⇒ x }
   * } yield b + "-" + c
   * </pre>
   */
  @deprecated("No longer needed, use 'map' instead. Removed in 2.0", "2.0")
  final def collect[A](pf: PartialFunction[T, A]): Future[A] = this map pf

  /**
   * Creates a new Future that will handle any matching Throwable that this
   * Future might contain. If there is no match, or if this Future contains
   * a valid result then the new Future will contain the same.
   * Example:
   * <pre>
   * Future(6 / 0) failure { case e: ArithmeticException ⇒ 0 } // result: 0
   * Future(6 / 0) failure { case e: NotFoundException   ⇒ 0 } // result: exception
   * Future(6 / 2) failure { case e: ArithmeticException ⇒ 0 } // result: 3
   * </pre>
   */
  @deprecated("will be replaced by `recover`", "1.2")
  final def failure[A >: T](pf: PartialFunction[Throwable, A]): Future[A] = recover(pf)

  /**
   * Creates a new Future that will handle any matching Throwable that this
   * Future might contain. If there is no match, or if this Future contains
   * a valid result then the new Future will contain the same.
   * Example:
   * <pre>
   * Future(6 / 0) failure { case e: ArithmeticException ⇒ 0 } // result: 0
   * Future(6 / 0) failure { case e: NotFoundException   ⇒ 0 } // result: exception
   * Future(6 / 2) failure { case e: ArithmeticException ⇒ 0 } // result: 3
   * </pre>
   */
  final def recover[A >: T](pf: PartialFunction[Throwable, A]): Future[A] = {
    val fa = new DefaultCompletableFuture[A](timeoutInNanos, NANOS)
    onComplete {
      _.value.get match {
        case Left(e) if pf isDefinedAt e => fa.complete(try { Right(pf(e)) } catch { case x: Exception ⇒ Left(x) })
        case otherwise => fa complete otherwise
      }
    }
    fa
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
  final def map[A](f: T ⇒ A): Future[A] = {
    val fa = new DefaultCompletableFuture[A](timeoutInNanos, NANOS)
    onComplete {
      _.value.get match {
        case l: Left[_, _] ⇒ fa complete l.asInstanceOf[Either[Throwable, A]]
        case Right(res) ⇒
          fa complete (try {
            Right(f(res))
          } catch {
            case e: Exception ⇒
              EventHandler.error(e, this, e.getMessage)
              Left(e)
          })
      }
    }
    fa
  }

  /**
   * Creates a new Future[A] which is completed with this Future's result if
   * that conforms to A's erased type or a ClassCastException otherwise.
   */
  final def mapTo[A](implicit m: Manifest[A]): Future[A] = {
    val fa = new DefaultCompletableFuture[A](timeoutInNanos, NANOS)
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
  final def flatMap[A](f: T ⇒ Future[A]): Future[A] = {
    val fa = new DefaultCompletableFuture[A](timeoutInNanos, NANOS)
    onComplete {
      _.value.get match {
        case l: Left[_, _] ⇒ fa complete l.asInstanceOf[Either[Throwable, A]]
        case Right(r) ⇒ try {
            fa.completeWith(f(r))
          } catch {
            case e: Exception ⇒
              EventHandler.error(e, this, e.getMessage)
              fa completeWithException e
          }
      }
    }
    fa
  }

  final def foreach(f: T ⇒ Unit): Unit = onComplete {
    _.value.get match {
      case Right(r) ⇒ f(r)
      case _ ⇒
    }
  }

  final def filter(p: Any ⇒ Boolean): Future[Any] = {
    val f = new DefaultCompletableFuture[T](timeoutInNanos, NANOS)
    onComplete {
      _.value.get match {
        case l: Left[_, _]  ⇒ f complete l.asInstanceOf[Either[Throwable, T]]
        case r @ Right(res) ⇒ f complete (try {
            if (p(res)) r else Left(new MatchError(res))
          } catch {
            case e: Exception ⇒
              EventHandler.error(e, this, e.getMessage)
              Left(e)
          })
      }
    }
    f
  }

  /**
   * Returns the current result, throws the exception is one has been raised, else returns None
   */
  final def resultOrException: Option[T] = value match {
    case Some(Left(e))  ⇒ throw e
    case Some(Right(r)) ⇒ Some(r)
    case _ ⇒ None
  }

  /**
   * Complete this Future with a FutureCancelledException if it has not been
   * completed, yet. If this is a compound Future (i.e. from Future.traverse
   * and friends), all contained Futures are canceled, the overall effect of
   * which depends on the nature of the compound Future.
   */
  def cancel(): this.type
}

package japi {
  /* Java API */
  trait Future[+T] { self: akka.dispatch.Future[T] ⇒
    private[japi] final def onTimeout[A >: T](proc: Procedure[akka.dispatch.Future[A]]): this.type = self.onTimeout(proc(_))
    private[japi] final def onResult[A >: T](proc: Procedure[A]): this.type = self.onResult({ case r: A => proc(r) }: PartialFunction[T, Unit])
    private[japi] final def onException(proc: Procedure[Throwable]): this.type = self.onException({ case t: Throwable => proc(t) }: PartialFunction[Throwable,Unit])
    private[japi] final def onComplete[A >: T](proc: Procedure[akka.dispatch.Future[A]]): this.type = self.onComplete(proc(_))
    private[japi] final def map[A >: T, B](f: JFunc[A, B]): akka.dispatch.Future[B] = self.map(f(_))
    private[japi] final def flatMap[A >: T, B](f: JFunc[A, akka.dispatch.Future[B]]): akka.dispatch.Future[B] = self.flatMap(f(_))
    private[japi] final def foreach[A >: T](proc: Procedure[A]): Unit = self.foreach(proc(_))
    private[japi] final def filter[A >: T](p: JFunc[A, java.lang.Boolean]): akka.dispatch.Future[A] =
      self.filter((a: Any) => p(a.asInstanceOf[A])).asInstanceOf[akka.dispatch.Future[A]]
  }
}

object Promise {

  def apply[A](timeout: Long)(implicit dispatcher: MessageDispatcher): CompletableFuture[A] = new DefaultCompletableFuture[A](timeout)

  def apply[A]()(implicit dispatcher: MessageDispatcher): CompletableFuture[A] = apply(Actor.TIMEOUT)

}

/**
 * Essentially this is the Promise (or write-side) of a Future (read-side).
 */
trait CompletableFuture[T] extends Future[T] {
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
    val fr = new DefaultCompletableFuture[Any](Actor.TIMEOUT)
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
class DefaultCompletableFuture[T](timeout: Long, timeunit: TimeUnit)(implicit val dispatcher: MessageDispatcher) extends CompletableFuture[T] {

  def this()(implicit dispatcher: MessageDispatcher) = this(Actor.TIMEOUT, MILLIS)

  def this(timeout: Long)(implicit dispatcher: MessageDispatcher) = this(timeout, MILLIS)

  val timeoutInNanos = timeunit.toNanos(timeout)
  private val _startTimeInNanos = currentTimeInNanos
  private val ref = new AtomicReference[FState[T]](FState())

  @tailrec
  private def awaitUnsafe(waitTimeNanos: Long): Boolean = {
    if (value.isEmpty && waitTimeNanos > 0) {
      val ms = NANOS.toMillis(waitTimeNanos)
      val ns = (waitTimeNanos % 1000000l).toInt //As per object.wait spec
      val start = System.nanoTime()
      try { ref.synchronized { if (value.isEmpty) ref.wait(ms,ns) } } catch { case e: InterruptedException ⇒ }

      awaitUnsafe(waitTimeNanos - math.abs(System.nanoTime() - start))
    } else {
      value.isDefined
    }
  }

  protected def awaitThenThrow(waitNanos: Long): this.type = if (value.isDefined) this else {
    Future.blocking()
    if ( awaitUnsafe(waitNanos) ) this
    else throw new FutureTimeoutException("Futures timed out after [" + NANOS.toMillis(waitNanos) + "] milliseconds")
  }

  def await(atMost: Duration) = awaitThenThrow(atMost.toNanos min timeLeft())

  def await = awaitThenThrow(timeLeft())

  def isExpired: Boolean = timeLeft() <= 0

  def value: Option[Either[Throwable, T]] = ref.get.value

  def complete(value: Either[Throwable, T]): this.type = {
    val callbacks = {
      try {
        @tailrec
        def tryComplete: List[Future[T] ⇒ Unit] = {
          val cur = ref.get
          if (cur.value.isDefined) Nil
          else if (/*cur.value.isEmpty && */isExpired) {
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
      if (value.isEmpty) {
        if (!isExpired) {
          val runnable = new Runnable { def run() { if (!isCompleted) func(DefaultCompletableFuture.this) } } //TODO Reschedule is run prematurely
          Scheduler.scheduleOnce(runnable, timeLeft, NANOS)
          false
        } else true
      } else false

    if (runNow) Future.dispatchTask(() ⇒ notifyCompleted(func))

    this
  }

  private def notifyCompleted(func: Future[T] ⇒ Unit) {
    try { func(this) } catch { case e ⇒ EventHandler notify EventHandler.Error(e, this) } //TODO catch, everything? Really?
  }

  def cancel(): this.type = {
    complete(Left(new FutureCanceledException))
    this
  }

  @inline
  private def currentTimeInNanos: Long = MILLIS.toNanos(System.currentTimeMillis)
  @inline
  private def timeLeft(): Long = timeoutInNanos - (currentTimeInNanos - _startTimeInNanos)
}

class CompositePromise[T, R](futures: Iterable[Future[T]], timeout: Long)(implicit dispatcher: MessageDispatcher)
  extends DefaultCompletableFuture[R](timeout) {

  override def cancel(): this.type = {
    futures foreach (_.cancel())
    this
  }
}

class ActorCompletableFuture(timeout: Long, timeunit: TimeUnit)(implicit dispatcher: MessageDispatcher)
  extends DefaultCompletableFuture[Any](timeout, timeunit)(dispatcher)
  with ForwardableChannel with ExceptionChannel[Any] {
  def this()(implicit dispatcher: MessageDispatcher) = this(0, MILLIS)
  def this(timeout: Long)(implicit dispatcher: MessageDispatcher) = this(timeout, MILLIS)

  def !(message: Any)(implicit channel: UntypedChannel) = completeWithResult(message)

  override def sendException(ex: Throwable) = {
    completeWithException(ex)
    value == Some(Left(ex))
  }

  def channel: UntypedChannel = this

  @deprecated("ActorCompletableFuture merged with Channel[Any], just use 'this'", "1.2")
  def future = this
}

object ActorCompletableFuture {
  def apply(f: CompletableFuture[Any]): ActorCompletableFuture =
    new ActorCompletableFuture(f.timeoutInNanos, NANOS)(f.dispatcher) {
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
sealed class AlreadyCompletedFuture[T](suppliedValue: Either[Throwable, T], timeout: Long, timeunit: TimeUnit)(implicit val dispatcher: MessageDispatcher) extends CompletableFuture[T] {
  def this(suppliedValue: Either[Throwable, T])(implicit dispatcher: MessageDispatcher) = this(suppliedValue, Actor.TIMEOUT, MILLIS)(dispatcher)
  def this(suppliedValue: Either[Throwable, T], timeout: Long)(implicit dispatcher: MessageDispatcher) = this(suppliedValue, timeout, MILLIS)(dispatcher)

  val value = Some(suppliedValue)
  val timeoutInNanos: Long = timeunit.toNanos(timeout)

  def complete(value: Either[Throwable, T]): this.type = this
  def onComplete(func: Future[T] ⇒ Unit): this.type = {
    Future dispatchTask (() ⇒ func(this))
    this
  }
  def await(atMost: Duration): this.type = this
  def await: this.type = this
  def isExpired: Boolean = true
  def onTimeout(func: Future[T] ⇒ Unit): this.type = this
  def cancel(): this.type = this
}
