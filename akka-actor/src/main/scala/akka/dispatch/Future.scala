/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import akka.AkkaException
import akka.event.Logging.Error
import scala.util.Timeout
import scala.Option
import akka.japi.{ Procedure, Function ⇒ JFunc, Option ⇒ JOption }

import scala.util.continuations._

import java.util.concurrent.TimeUnit.{ NANOSECONDS, MILLISECONDS }
import java.lang.{ Iterable ⇒ JIterable }
import java.util.{ LinkedList ⇒ JLinkedList }

import scala.annotation.tailrec
import scala.collection.mutable.Stack
import scala.util.{ Duration }
import akka.util.{ Switch, BoxedType }
import java.util.concurrent.atomic.{ AtomicReferenceFieldUpdater, AtomicInteger, AtomicBoolean }
import akka.dispatch.Await.CanAwait
import java.util.concurrent._
import akka.actor.ActorSystem

object Await {
  sealed trait CanAwait

  trait Awaitable[+T] {
    /**
     * Should throw java.util.concurrent.TimeoutException if times out
     * This method should not be called directly.
     */
    def ready(atMost: Duration)(implicit permit: CanAwait): this.type

    /**
     * Throws exceptions if cannot produce a T within the specified time
     * This method should not be called directly.
     */
    def result(atMost: Duration)(implicit permit: CanAwait): T
  }

  private[this] implicit final val permit = new CanAwait {}

  def ready[T <: Awaitable[_]](awaitable: T, atMost: Duration): T = awaitable.ready(atMost)
  def result[T](awaitable: Awaitable[T], atMost: Duration): T = awaitable.result(atMost)
}

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
  def promise[T](executor: ExecutionContext): Promise[T] = Promise[T]()(executor)

  /**
   * Java API, creates an already completed Promise with the specified exception
   */
  def failed[T](exception: Throwable, executor: ExecutionContext): Promise[T] = Promise.failed(exception)(executor)

  /**
   * Java API, Creates an already completed Promise with the specified result
   */
  def successful[T](result: T, executor: ExecutionContext): Promise[T] = Promise.successful(result)(executor)

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
   * Initiates a fold over the supplied futures where the fold-zero is the result value of the Future that's completed first
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
}

object Future {

  /**
   * This method constructs and returns a Future that will eventually hold the result of the execution of the supplied body
   * The execution is performed by the specified Dispatcher.
   */
  def apply[T](body: ⇒ T)(implicit executor: ExecutionContext): Future[T] = {
    val promise = Promise[T]()
    executor.execute(new Runnable {
      def run =
        promise complete {
          try {
            Right(body)
          } catch {
            // TODO catching all and continue isn't good for OOME, ticket #1418
            case e ⇒ Left(e)
          }
        }
    })
    promise
  }

  import scala.collection.mutable.Builder
  import scala.collection.generic.CanBuildFrom

  /**
   * Simple version of Futures.traverse. Transforms a Traversable[Future[A]] into a Future[Traversable[A]].
   * Useful for reducing many Futures into a single Future.
   */
  def sequence[A, M[_] <: Traversable[_]](in: M[Future[A]])(implicit cbf: CanBuildFrom[M[Future[A]], A, M[A]], executor: ExecutionContext): Future[M[A]] =
    in.foldLeft(Promise.successful(cbf(in)): Future[Builder[A, M[A]]])((fr, fa) ⇒ for (r ← fr; a ← fa.asInstanceOf[Future[A]]) yield (r += a)).map(_.result)

  /**
   * Returns a Future to the result of the first future in the list that is completed
   */
  def firstCompletedOf[T](futures: Traversable[Future[T]])(implicit executor: ExecutionContext): Future[T] = {
    val futureResult = Promise[T]()

    val completeFirst: Either[Throwable, T] ⇒ Unit = futureResult complete _
    futures.foreach(_ onComplete completeFirst)

    futureResult
  }

  /**
   * Returns a Future that will hold the optional result of the first Future with a result that matches the predicate
   */
  def find[T](futures: Traversable[Future[T]])(predicate: T ⇒ Boolean)(implicit executor: ExecutionContext): Future[Option[T]] = {
    if (futures.isEmpty) Promise.successful[Option[T]](None)
    else {
      val result = Promise[Option[T]]()
      val ref = new AtomicInteger(futures.size)
      val search: Either[Throwable, T] ⇒ Unit = v ⇒ try {
        v match {
          case Right(r) ⇒ if (predicate(r)) result success Some(r)
          case _        ⇒
        }
      } finally {
        if (ref.decrementAndGet == 0)
          result success None
      }

      futures.foreach(_ onComplete search)

      result
    }
  }

  /**
   * A non-blocking fold over the specified futures, with the start value of the given zero.
   * The fold is performed on the thread where the last future is completed,
   * the result will be the first failure of any of the futures, or any failure in the actual fold,
   * or the result of the fold.
   * Example:
   * <pre>
   *   val result = Await.result(Future.fold(futures)(0)(_ + _), 5 seconds)
   * </pre>
   */
  def fold[T, R](futures: Traversable[Future[T]])(zero: R)(foldFun: (R, T) ⇒ R)(implicit executor: ExecutionContext): Future[R] = {
    if (futures.isEmpty) Promise.successful(zero)
    else sequence(futures).map(_.foldLeft(zero)(foldFun))
  }

  /**
   * Initiates a fold over the supplied futures where the fold-zero is the result value of the Future that's completed first
   * Example:
   * <pre>
   *   val result = Await.result(Futures.reduce(futures)(_ + _), 5 seconds)
   * </pre>
   */
  def reduce[T, R >: T](futures: Traversable[Future[T]])(op: (R, T) ⇒ R)(implicit executor: ExecutionContext): Future[R] = {
    if (futures.isEmpty) Promise[R].failure(new NoSuchElementException("reduce attempted on empty collection"))
    else sequence(futures).map(_ reduceLeft op)
  }
  /**
   * Transforms a Traversable[A] into a Future[Traversable[B]] using the provided Function A ⇒ Future[B].
   * This is useful for performing a parallel map. For example, to apply a function to all items of a list
   * in parallel:
   * <pre>
   * val myFutureList = Future.traverse(myList)(x ⇒ Future(myFunc(x)))
   * </pre>
   */
  def traverse[A, B, M[_] <: Traversable[_]](in: M[A])(fn: A ⇒ Future[B])(implicit cbf: CanBuildFrom[M[A], B, M[B]], executor: ExecutionContext): Future[M[B]] =
    in.foldLeft(Promise.successful(cbf(in)): Future[Builder[B, M[B]]]) { (fr, a) ⇒
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
   * Completing a Future using 'Promise << Future' will also suspend execution until the
   * value of the other Future is available.
   *
   * The Delimited Continuations compiler plugin must be enabled in order to use this method.
   */
  def flow[A](body: ⇒ A @cps[Future[Any]])(implicit executor: ExecutionContext): Future[A] = {
    val future = Promise[A]
    dispatchTask({ () ⇒
      (reify(body) foreachFull (future success, future failure): Future[Any]) onFailure {
        case e: Exception ⇒ future failure e
      }
    }, true)
    future
  }

  /**
   * Signals that the current thread of execution will potentially engage
   * in blocking calls after the call to this method, giving the system a
   * chance to spawn new threads, reuse old threads or otherwise, to prevent
   * starvation and/or unfairness.
   *
   * Assures that any Future tasks initiated in the current thread will be
   * executed asynchronously, including any tasks currently queued to be
   * executed in the current thread. This is needed if the current task may
   * block, causing delays in executing the remaining tasks which in some
   * cases may cause a deadlock.
   *
   * Note: Calling 'Await.result(future)' or 'Await.ready(future)' will automatically trigger this method.
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
  def blocking(implicit executor: ExecutionContext): Unit =
    _taskStack.get match {
      case stack if (stack ne null) && stack.nonEmpty ⇒
        val tasks = stack.elems
        stack.clear()
        _taskStack.remove()
        dispatchTask(() ⇒ _taskStack.get.elems = tasks, true)
      case _ ⇒ _taskStack.remove()
    }

  private val _taskStack = new ThreadLocal[Stack[() ⇒ Unit]]()

  /**
   * Internal API, do not call
   */
  private[akka] def dispatchTask(task: () ⇒ Unit, force: Boolean = false)(implicit executor: ExecutionContext): Unit =
    _taskStack.get match {
      case stack if (stack ne null) && !force ⇒ stack push task
      case _ ⇒ executor.execute(
        new Runnable {
          def run =
            try {

              val taskStack = Stack.empty[() ⇒ Unit]
              taskStack push task
              _taskStack set taskStack

              while (taskStack.nonEmpty) {
                val next = taskStack.pop()
                try {
                  next.apply()
                } catch {
                  case e ⇒
                    // TODO catching all and continue isn't good for OOME, ticket #1418
                    executor match {
                      case m: MessageDispatcher ⇒
                        m.prerequisites.eventStream.publish(Error(e, "Future.dispatchTask", this.getClass, e.getMessage))
                      case other ⇒
                        e.printStackTrace()
                    }
                }
              }
            } finally { _taskStack.remove() }
        })
    }
}

sealed trait Future[+T] extends japi.Future[T] with Await.Awaitable[T] {

  implicit def executor: ExecutionContext

  protected final def resolve[X](source: Either[Throwable, X]): Either[Throwable, X] = source match {
    case Left(t: scala.runtime.NonLocalReturnControl[_]) ⇒ Right(t.value.asInstanceOf[X])
    case Left(t: InterruptedException) ⇒ Left(new RuntimeException("Boxed InterruptedException", t))
    case _ ⇒ source
  }

  /**
   * @return a new Future that will contain a tuple containing the successful result of this and that Future.
   * If this or that fail, they will race to complete the returned Future with their failure.
   * The returned Future will not be completed if neither this nor that are completed.
   */
  def zip[U](that: Future[U]): Future[(T, U)] = {
    val p = Promise[(T, U)]()
    onComplete {
      case Left(t)  ⇒ p failure t
      case Right(r) ⇒ that onSuccess { case r2 ⇒ p success ((r, r2)) }
    }
    that onFailure { case f ⇒ p failure f }
    p
  }

  /**
   * For use only within a Future.flow block or another compatible Delimited Continuations reset block.
   *
   * Returns the result of this Future without blocking, by suspending execution and storing it as a
   * continuation until the result is available.
   */
  def apply(): T @cps[Future[Any]] = shift(this flatMap (_: T ⇒ Future[Any]))

  /**
   * Tests whether this Future has been completed.
   */
  def isCompleted: Boolean

  /**
   * The contained value of this Future. Before this Future is completed
   * the value will be None. After completion the value will be Some(Right(t))
   * if it contains a valid result, or Some(Left(error)) if it contains
   * an exception.
   */
  def value: Option[Either[Throwable, T]]

  /**
   * When this Future is completed, apply the provided function to the
   * Future. If the Future has already been completed, this will apply
   * immediately. Multiple
   * callbacks may be registered; there is no guarantee that they will be
   * executed in a particular order.
   */
  def onComplete(func: Either[Throwable, T] ⇒ Unit): this.type

  /**
   * When the future is completed with a valid result, apply the provided
   * PartialFunction to the result. See `onComplete` for more details.
   * <pre>
   *   future onSuccess {
   *     case Foo ⇒ target ! "foo"
   *     case Bar ⇒ target ! "bar"
   *   }
   * </pre>
   */
  final def onSuccess[U](pf: PartialFunction[T, U]): this.type = onComplete {
    case Right(r) if pf isDefinedAt r ⇒ pf(r)
    case _                            ⇒
  }

  /**
   * When the future is completed with an exception, apply the provided
   * PartialFunction to the exception. See `onComplete` for more details.
   * <pre>
   *   future onFailure {
   *     case NumberFormatException ⇒ target ! "wrong format"
   *   }
   * </pre>
   */
  final def onFailure[U](pf: PartialFunction[Throwable, U]): this.type = onComplete {
    case Left(ex) if pf isDefinedAt ex ⇒ pf(ex)
    case _                             ⇒
  }

  /**
   * Returns a failure projection of this Future
   * If `this` becomes completed with a failure, that failure will be the success of the returned Future
   * If `this` becomes completed with a result, then the returned future will fail with a NoSuchElementException
   */
  final def failed: Future[Throwable] = {
    val p = Promise[Throwable]()
    this.onComplete {
      case Left(t)  ⇒ p success t
      case Right(r) ⇒ p failure new NoSuchElementException("Future.failed not completed with a throwable. Instead completed with: " + r)
    }
    p
  }

  /**
   * Returns a new Future that will either hold the successful value of this Future,
   * or, it this Future fails, it will hold the result of "that" Future.
   */
  def or[U >: T](that: Future[U]): Future[U] = {
    val p = Promise[U]()
    onComplete {
      case r @ Right(_) ⇒ p complete r
      case _            ⇒ p completeWith that
    }
    p
  }

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
  final def recover[A >: T](pf: PartialFunction[Throwable, A]): Future[A] = {
    val future = Promise[A]()
    onComplete {
      case Left(e) if pf isDefinedAt e ⇒ future.complete(try { Right(pf(e)) } catch { case x: Exception ⇒ Left(x) })
      case otherwise                   ⇒ future complete otherwise
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
  final def map[A](f: T ⇒ A): Future[A] = {
    val future = Promise[A]()
    onComplete {
      case l: Left[_, _] ⇒ future complete l.asInstanceOf[Either[Throwable, A]]
      case Right(res) ⇒
        future complete (try {
          Right(f(res))
        } catch {
          case e ⇒
            logError("Future.map", e)
            Left(e)
        })
    }
    future
  }

  /**
   * Creates a new Future[A] which is completed with this Future's result if
   * that conforms to A's erased type or a ClassCastException otherwise.
   */
  final def mapTo[A](implicit m: Manifest[A]): Future[A] = {
    val fa = Promise[A]()
    onComplete {
      case l: Left[_, _] ⇒ fa complete l.asInstanceOf[Either[Throwable, A]]
      case Right(t) ⇒
        fa complete (try {
          Right(BoxedType(m.erasure).cast(t).asInstanceOf[A])
        } catch {
          case e: ClassCastException ⇒ Left(e)
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
    val p = Promise[A]()

    onComplete {
      case l: Left[_, _] ⇒ p complete l.asInstanceOf[Either[Throwable, A]]
      case Right(r) ⇒
        try {
          p completeWith f(r)
        } catch {
          case e ⇒
            p complete Left(e)
            logError("Future.flatMap", e)
        }
    }
    p
  }

  /**
   * Same as onSuccess { case r => f(r) } but is also used in for-comprehensions
   */
  final def foreach(f: T ⇒ Unit): Unit = onComplete {
    case Right(r) ⇒ f(r)
    case _        ⇒
  }

  /**
   * Used by for-comprehensions
   */
  final def withFilter(p: T ⇒ Boolean) = new FutureWithFilter[T](this, p)

  final class FutureWithFilter[+A](self: Future[A], p: A ⇒ Boolean) {
    def foreach(f: A ⇒ Unit): Unit = self filter p foreach f
    def map[B](f: A ⇒ B): Future[B] = self filter p map f
    def flatMap[B](f: A ⇒ Future[B]): Future[B] = self filter p flatMap f
    def withFilter(q: A ⇒ Boolean): FutureWithFilter[A] = new FutureWithFilter[A](self, x ⇒ p(x) && q(x))
  }

  /**
   * Returns a new Future that will hold the successful result of this Future if it matches
   * the given predicate, if it doesn't match, the resulting Future will be a failed Future
   * with a MatchError, of if this Future fails, that failure will be propagated to the returned Future
   */
  final def filter(pred: T ⇒ Boolean): Future[T] = {
    val p = Promise[T]()
    onComplete {
      case l: Left[_, _] ⇒ p complete l.asInstanceOf[Either[Throwable, T]]
      case r @ Right(res) ⇒ p complete (try {
        if (pred(res)) r else Left(new MatchError(res))
      } catch {
        case e ⇒
          logError("Future.filter", e)
          Left(e)
      })
    }
    p
  }

  protected def logError(msg: String, problem: Throwable): Unit = {
    executor match {
      case m: MessageDispatcher ⇒ m.prerequisites.eventStream.publish(Error(problem, msg, this.getClass, problem.getMessage))
      case other                ⇒ problem.printStackTrace()
    }
  }
}

object Promise {
  /**
   * Creates a non-completed Promise
   *
   * Scala API
   */
  def apply[A]()(implicit executor: ExecutionContext): Promise[A] = new DefaultPromise[A]()

  /**
   * Creates an already completed Promise with the specified exception
   */
  def failed[T](exception: Throwable)(implicit executor: ExecutionContext): Promise[T] = new KeptPromise[T](Left(exception))

  /**
   * Creates an already completed Promise with the specified result
   */
  def successful[T](result: T)(implicit executor: ExecutionContext): Promise[T] = new KeptPromise[T](Right(result))
}

/**
 * Essentially this is the Promise (or write-side) of a Future (read-side).
 */
trait Promise[T] extends Future[T] {

  /**
   * Returns the Future associated with this Promise
   */
  def future: Future[T] = this

  /**
   * Completes this Promise with the specified result, if not already completed.
   * @return whether this call completed the Promise
   */
  def tryComplete(value: Either[Throwable, T]): Boolean

  /**
   * Completes this Promise with the specified result, if not already completed.
   * @return this
   */
  final def complete(value: Either[Throwable, T]): this.type = { tryComplete(value); this }

  /**
   * Completes this Promise with the specified result, if not already completed.
   * @return this
   */
  final def success(result: T): this.type = complete(Right(result))

  /**
   * Completes this Promise with the specified exception, if not already completed.
   * @return this
   */
  final def failure(exception: Throwable): this.type = complete(Left(exception))

  /**
   * Completes this Promise with the specified other Future, when that Future is completed,
   * unless this Promise has already been completed.
   * @return this.
   */
  final def completeWith(other: Future[T]): this.type = {
    other onComplete { complete(_) }
    this
  }

  final def <<(value: T): Future[T] @cps[Future[Any]] = shift { cont: (Future[T] ⇒ Future[Any]) ⇒ cont(complete(Right(value))) }

  final def <<(other: Future[T]): Future[T] @cps[Future[Any]] = shift { cont: (Future[T] ⇒ Future[Any]) ⇒
    val fr = Promise[Any]()
    val thisPromise = this
    thisPromise completeWith other onComplete { v ⇒
      try {
        fr completeWith cont(thisPromise)
      } catch {
        case e ⇒
          logError("Promise.completeWith", e)
          fr failure e
      }
    }
    fr
  }

  final def <<(stream: PromiseStreamOut[T]): Future[T] @cps[Future[Any]] = shift { cont: (Future[T] ⇒ Future[Any]) ⇒
    val fr = Promise[Any]()
    val f = stream.dequeue(this)
    f.onComplete { _ ⇒
      try {
        fr completeWith cont(f)
      } catch {
        case e ⇒
          logError("Promise.completeWith", e)
          fr failure e
      }
    }
    fr
  }
}

//Companion object to FState, just to provide a cheap, immutable default entry
private[dispatch] object DefaultPromise {
  def EmptyPending[T](): List[T] = Nil
}

/**
 * The default concrete Future implementation.
 */
class DefaultPromise[T](implicit val executor: ExecutionContext) extends AbstractPromise with Promise[T] {
  self ⇒

  protected final def tryAwait(atMost: Duration): Boolean = {
    Future.blocking

    @tailrec
    def awaitUnsafe(waitTimeNanos: Long): Boolean = {
      if (!isCompleted && waitTimeNanos > 0) {
        val ms = NANOSECONDS.toMillis(waitTimeNanos)
        val ns = (waitTimeNanos % 1000000l).toInt //As per object.wait spec
        val start = System.nanoTime()
        try { synchronized { if (!isCompleted) wait(ms, ns) } } catch { case e: InterruptedException ⇒ }

        awaitUnsafe(waitTimeNanos - (System.nanoTime() - start))
      } else isCompleted
    }
    awaitUnsafe(if (atMost.isFinite) atMost.toNanos else Long.MaxValue)
  }

  def ready(atMost: Duration)(implicit permit: CanAwait): this.type =
    if (isCompleted || tryAwait(atMost)) this
    else throw new TimeoutException("Futures timed out after [" + atMost.toMillis + "] milliseconds")

  def result(atMost: Duration)(implicit permit: CanAwait): T =
    ready(atMost).value.get match {
      case Left(e)  ⇒ throw e
      case Right(r) ⇒ r
    }

  def value: Option[Either[Throwable, T]] = getState match {
    case _: List[_]      ⇒ None
    case c: Either[_, _] ⇒ Some(c.asInstanceOf[Either[Throwable, T]])
  }

  def isCompleted(): Boolean = getState match {
    case _: Either[_, _] ⇒ true
    case _               ⇒ false
  }

  @inline
  private[this] final def updater = AbstractPromise.updater.asInstanceOf[AtomicReferenceFieldUpdater[AbstractPromise, AnyRef]]

  @inline
  protected final def updateState(oldState: AnyRef, newState: AnyRef): Boolean = updater.compareAndSet(this, oldState, newState)

  @inline
  protected final def getState: AnyRef = updater.get(this)

  def tryComplete(value: Either[Throwable, T]): Boolean = {
    val callbacks: List[Either[Throwable, T] ⇒ Unit] = {
      try {
        @tailrec
        def tryComplete(v: Either[Throwable, T]): List[Either[Throwable, T] ⇒ Unit] = {
          getState match {
            case raw: List[_] ⇒
              val cur = raw.asInstanceOf[List[Either[Throwable, T] ⇒ Unit]]
              if (updateState(cur, v)) cur else tryComplete(v)
            case _ ⇒ null
          }
        }
        tryComplete(resolve(value))
      } finally {
        synchronized { notifyAll() } //Notify any evil blockers
      }
    }

    callbacks match {
      case null             ⇒ false
      case cs if cs.isEmpty ⇒ true
      case cs               ⇒ Future.dispatchTask(() ⇒ cs.foreach(f ⇒ notifyCompleted(f, value))); true
    }
  }

  def onComplete(func: Either[Throwable, T] ⇒ Unit): this.type = {
    @tailrec //Returns whether the future has already been completed or not
    def tryAddCallback(): Either[Throwable, T] = {
      val cur = getState
      cur match {
        case r: Either[_, _]    ⇒ r.asInstanceOf[Either[Throwable, T]]
        case listeners: List[_] ⇒ if (updateState(listeners, func :: listeners)) null else tryAddCallback()
      }
    }

    tryAddCallback() match {
      case null ⇒ this
      case completed ⇒
        Future.dispatchTask(() ⇒ notifyCompleted(func, completed))
        this
    }
  }

  private final def notifyCompleted(func: Either[Throwable, T] ⇒ Unit, result: Either[Throwable, T]) {
    try { func(result) } catch { case e ⇒ logError("Future onComplete-callback raised an exception", e) }
  }
}

/**
 * An already completed Future is seeded with it's result at creation, is useful for when you are participating in
 * a Future-composition but you already have a value to contribute.
 */
final class KeptPromise[T](suppliedValue: Either[Throwable, T])(implicit val executor: ExecutionContext) extends Promise[T] {
  val value = Some(resolve(suppliedValue))

  def tryComplete(value: Either[Throwable, T]): Boolean = false
  def onComplete(func: Either[Throwable, T] ⇒ Unit): this.type = {
    val completedAs = value.get
    Future dispatchTask (() ⇒ func(completedAs))
    this
  }
  def isCompleted(): Boolean = true
  def ready(atMost: Duration)(implicit permit: CanAwait): this.type = this
  def result(atMost: Duration)(implicit permit: CanAwait): T = value.get match {
    case Left(e)  ⇒ throw e
    case Right(r) ⇒ r
  }
}
