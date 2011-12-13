
/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import akka.AkkaException
import akka.event.Logging.Error
import akka.actor.Timeout
import scala.Option
import akka.japi.{ Procedure, Function ⇒ JFunc, Option ⇒ JOption }

import scala.util.continuations._

import java.util.concurrent.TimeUnit.{ NANOSECONDS, MILLISECONDS }
import java.lang.{ Iterable ⇒ JIterable }
import java.util.{ LinkedList ⇒ JLinkedList }

import scala.annotation.tailrec
import scala.collection.mutable.Stack
import akka.util.{ Switch, Duration, BoxedType }
import java.util.concurrent.atomic.{ AtomicReferenceFieldUpdater, AtomicInteger, AtomicBoolean }
import java.util.concurrent.{ TimeoutException, ConcurrentLinkedQueue, TimeUnit, Callable }
import akka.dispatch.Await.CanAwait

object Await {
  sealed trait CanAwait

  trait Awaitable[+T] {
    /**
     * Should throw java.util.concurrent.TimeoutException if times out
     */
    def ready(atMost: Duration)(implicit permit: CanAwait): this.type

    /**
     * Throws exceptions if cannot produce a T within the specified time
     */
    def result(atMost: Duration)(implicit permit: CanAwait): T
  }

  private implicit val permit = new CanAwait {}

  def ready[T <: Awaitable[_]](awaitable: T, atMost: Duration): T = awaitable.ready(atMost)
  def result[T](awaitable: Awaitable[T], atMost: Duration): T = awaitable.result(atMost)
}

object Futures {

  /**
   * Java API, equivalent to Future.apply
   */
  def future[T](body: Callable[T], dispatcher: MessageDispatcher): Future[T] = Future(body.call)(dispatcher)

  /**
   * Java API, equivalent to Promise.apply
   */
  def promise[T](dispatcher: MessageDispatcher): Promise[T] = Promise[T]()(dispatcher)

  /**
   * Java API.
   * Returns a Future that will hold the optional result of the first Future with a result that matches the predicate
   */
  def find[T <: AnyRef](futures: JIterable[Future[T]], predicate: JFunc[T, java.lang.Boolean], dispatcher: MessageDispatcher): Future[JOption[T]] = {
    Future.find[T]((scala.collection.JavaConversions.iterableAsScalaIterable(futures)))(predicate.apply(_))(dispatcher).map(JOption.fromScalaOption(_))
  }

  /**
   * Java API.
   * Returns a Future to the result of the first future in the list that is completed
   */
  def firstCompletedOf[T <: AnyRef](futures: JIterable[Future[T]], dispatcher: MessageDispatcher): Future[T] =
    Future.firstCompletedOf(scala.collection.JavaConversions.iterableAsScalaIterable(futures))(dispatcher)

  /**
   * Java API
   * A non-blocking fold over the specified futures.
   * The fold is performed on the thread where the last future is completed,
   * the result will be the first failure of any of the futures, or any failure in the actual fold,
   * or the result of the fold.
   */
  def fold[T <: AnyRef, R <: AnyRef](zero: R, futures: java.lang.Iterable[Future[T]], fun: akka.japi.Function2[R, T, R], dispatcher: MessageDispatcher): Future[R] =
    Future.fold(scala.collection.JavaConversions.iterableAsScalaIterable(futures))(zero)(fun.apply _)(dispatcher)

  /**
   * Java API.
   * Initiates a fold over the supplied futures where the fold-zero is the result value of the Future that's completed first
   */
  def reduce[T <: AnyRef, R >: T](futures: java.lang.Iterable[Future[T]], fun: akka.japi.Function2[R, T, T], dispatcher: MessageDispatcher): Future[R] =
    Future.reduce(scala.collection.JavaConversions.iterableAsScalaIterable(futures))(fun.apply _)(dispatcher)

  /**
   * Java API.
   * Simple version of Future.traverse. Transforms a java.lang.Iterable[Future[A]] into a Future[java.lang.Iterable[A]].
   * Useful for reducing many Futures into a single Future.
   */
  def sequence[A](in: JIterable[Future[A]], dispatcher: MessageDispatcher): Future[JIterable[A]] = {
    implicit val d = dispatcher
    scala.collection.JavaConversions.iterableAsScalaIterable(in).foldLeft(Future(new JLinkedList[A]()))((fr, fa) ⇒
      for (r ← fr; a ← fa) yield {
        r add a
        r
      })
  }

  /**
   * Java API.
   * Transforms a java.lang.Iterable[A] into a Future[java.lang.Iterable[B]] using the provided Function A ⇒ Future[B].
   * This is useful for performing a parallel map. For example, to apply a function to all items of a list
   * in parallel.
   */
  def traverse[A, B](in: JIterable[A], fn: JFunc[A, Future[B]], dispatcher: MessageDispatcher): Future[JIterable[B]] = {
    implicit val d = dispatcher
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
  def apply[T](body: ⇒ T)(implicit dispatcher: MessageDispatcher): Future[T] = {
    val promise = Promise[T]()
    dispatcher dispatchTask { () ⇒
      promise complete {
        try {
          Right(body)
        } catch {
          // FIXME catching all and continue isn't good for OOME, ticket #1418
          case e ⇒ Left(e)
        }
      }
    }
    promise
  }

  import scala.collection.mutable.Builder
  import scala.collection.generic.CanBuildFrom

  /**
   * Simple version of Futures.traverse. Transforms a Traversable[Future[A]] into a Future[Traversable[A]].
   * Useful for reducing many Futures into a single Future.
   */
  def sequence[A, M[_] <: Traversable[_]](in: M[Future[A]])(implicit cbf: CanBuildFrom[M[Future[A]], A, M[A]], dispatcher: MessageDispatcher): Future[M[A]] =
    in.foldLeft(Promise.successful(cbf(in)): Future[Builder[A, M[A]]])((fr, fa) ⇒ for (r ← fr; a ← fa.asInstanceOf[Future[A]]) yield (r += a)).map(_.result)

  /**
   * Returns a Future to the result of the first future in the list that is completed
   */
  def firstCompletedOf[T](futures: Iterable[Future[T]])(implicit dispatcher: MessageDispatcher): Future[T] = {
    val futureResult = Promise[T]()

    val completeFirst: Future[T] ⇒ Unit = _.value.foreach(futureResult complete _)
    futures.foreach(_ onComplete completeFirst)

    futureResult
  }

  /**
   * Returns a Future that will hold the optional result of the first Future with a result that matches the predicate
   */
  def find[T](futures: Iterable[Future[T]])(predicate: T ⇒ Boolean)(implicit dispatcher: MessageDispatcher): Future[Option[T]] = {
    if (futures.isEmpty) Promise.successful[Option[T]](None)
    else {
      val result = Promise[Option[T]]()
      val ref = new AtomicInteger(futures.size)
      val search: Future[T] ⇒ Unit = f ⇒ try {
        f.value.get match {
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
   * A non-blocking fold over the specified futures.
   * The fold is performed on the thread where the last future is completed,
   * the result will be the first failure of any of the futures, or any failure in the actual fold,
   * or the result of the fold.
   * Example:
   * <pre>
   *   val result = Futures.fold(0)(futures)(_ + _).await.result
   * </pre>
   */
  def fold[T, R](futures: Iterable[Future[T]])(zero: R)(foldFun: (R, T) ⇒ R)(implicit dispatcher: MessageDispatcher): Future[R] = {
    if (futures.isEmpty) Promise.successful(zero)
    else {
      val result = Promise[R]()
      val results = new ConcurrentLinkedQueue[T]()
      val done = new Switch(false)
      val allDone = futures.size

      val aggregate: Future[T] ⇒ Unit = f ⇒ if (done.isOff && !result.isCompleted) {
        f.value.get match {
          case Right(value) ⇒
            val added = results add value
            if (added && results.size == allDone) { //Only one thread can get here
              if (done.switchOn) {
                try {
                  val i = results.iterator
                  var currentValue = zero
                  while (i.hasNext) { currentValue = foldFun(currentValue, i.next) }
                  result success currentValue
                } catch {
                  case e: Exception ⇒
                    dispatcher.prerequisites.eventStream.publish(Error(e, "Future.fold", e.getMessage))
                    result failure e
                } finally {
                  results.clear
                }
              }
            }
          case Left(exception) ⇒
            if (done.switchOn) {
              result failure exception
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
  def reduce[T, R >: T](futures: Iterable[Future[T]])(op: (R, T) ⇒ T)(implicit dispatcher: MessageDispatcher): Future[R] = {
    if (futures.isEmpty) Promise[R].failure(new UnsupportedOperationException("empty reduce left"))
    else {
      val result = Promise[R]()
      val seedFound = new AtomicBoolean(false)
      val seedFold: Future[T] ⇒ Unit = f ⇒ {
        if (seedFound.compareAndSet(false, true)) { //Only the first completed should trigger the fold
          f.value.get match {
            case Right(value)    ⇒ result.completeWith(fold(futures.filterNot(_ eq f))(value)(op))
            case Left(exception) ⇒ result.failure(exception)
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
  def traverse[A, B, M[_] <: Traversable[_]](in: M[A])(fn: A ⇒ Future[B])(implicit cbf: CanBuildFrom[M[A], B, M[B]], dispatcher: MessageDispatcher): Future[M[B]] =
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
  def flow[A](body: ⇒ A @cps[Future[Any]])(implicit dispatcher: MessageDispatcher): Future[A] = {
    val future = Promise[A]
    dispatchTask({ () ⇒
      (reify(body) foreachFull (future success, future failure): Future[Any]) onFailure {
        case e: Exception ⇒ future failure e
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
  def blocking(implicit dispatcher: MessageDispatcher): Unit =
    _taskStack.get match {
      case Some(taskStack) if taskStack.nonEmpty ⇒
        val tasks = taskStack.elems
        taskStack.clear()
        _taskStack set None
        dispatchTask(() ⇒ _taskStack.get.get.elems = tasks, true)
      case Some(_) ⇒ _taskStack set None
      case _       ⇒ // already None
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
                case e ⇒
                  // FIXME catching all and continue isn't good for OOME, ticket #1418
                  dispatcher.prerequisites.eventStream.publish(Error(e, "Future.dispatchTask", "Failed to dispatch task, due to: " + e.getMessage))
              }
            }
          } finally { _taskStack set None }
        }
    }
}

sealed trait Future[+T] extends japi.Future[T] with Await.Awaitable[T] {

  implicit def dispatcher: MessageDispatcher

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
  final def isCompleted: Boolean = value.isDefined

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
  def onComplete(func: Future[T] ⇒ Unit): this.type

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
  final def onSuccess(pf: PartialFunction[T, Unit]): this.type = onComplete {
    _.value match {
      case Some(Right(r)) if pf isDefinedAt r ⇒ pf(r)
      case _                                  ⇒
    }
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
  final def onFailure(pf: PartialFunction[Throwable, Unit]): this.type = onComplete {
    _.value match {
      case Some(Left(ex)) if pf isDefinedAt ex ⇒ pf(ex)
      case _                                   ⇒
    }
  }

  /**
   * Creates a Future that will be the result of the first completed Future of this and the Future that was passed into this.
   * This is semantically the same as: Future.firstCompletedOf(Seq(this, that))
   */
  //FIXME implement as The result of any of the Futures, or if oth failed, the first failure
  def orElse[A >: T](that: Future[A]): Future[A] = Future.firstCompletedOf(List(this, that)) //TODO Optimize

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
  final def map[A](f: T ⇒ A): Future[A] = {
    val future = Promise[A]()
    onComplete {
      _.value.get match {
        case l: Left[_, _] ⇒ future complete l.asInstanceOf[Either[Throwable, A]]
        case Right(res) ⇒
          future complete (try {
            Right(f(res))
          } catch {
            case e: Exception ⇒
              dispatcher.prerequisites.eventStream.publish(Error(e, "Future.map", e.getMessage))
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
  final def mapTo[A](implicit m: Manifest[A]): Future[A] = {
    val fa = Promise[A]()
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
    val future = Promise[A]()

    onComplete {
      _.value.get match {
        case l: Left[_, _] ⇒ future complete l.asInstanceOf[Either[Throwable, A]]
        case Right(r) ⇒ try {
          future.completeWith(f(r))
        } catch {
          case e: Exception ⇒
            dispatcher.prerequisites.eventStream.publish(Error(e, "Future.flatMap", e.getMessage))
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

  final def withFilter(p: T ⇒ Boolean) = new FutureWithFilter[T](this, p)

  final class FutureWithFilter[+A](self: Future[A], p: A ⇒ Boolean) {
    def foreach(f: A ⇒ Unit): Unit = self filter p foreach f
    def map[B](f: A ⇒ B): Future[B] = self filter p map f
    def flatMap[B](f: A ⇒ Future[B]): Future[B] = self filter p flatMap f
    def withFilter(q: A ⇒ Boolean): FutureWithFilter[A] = new FutureWithFilter[A](self, x ⇒ p(x) && q(x))
  }

  final def filter(p: T ⇒ Boolean): Future[T] = {
    val future = Promise[T]()
    onComplete {
      _.value.get match {
        case l: Left[_, _] ⇒ future complete l.asInstanceOf[Either[Throwable, T]]
        case r @ Right(res) ⇒ future complete (try {
          if (p(res)) r else Left(new MatchError(res))
        } catch {
          case e: Exception ⇒
            dispatcher.prerequisites.eventStream.publish(Error(e, "Future.filter", e.getMessage))
            Left(e)
        })
      }
    }
    future
  }
}

object Promise {
  /**
   * Creates a non-completed Promise
   *
   * Scala API
   */
  def apply[A]()(implicit dispatcher: MessageDispatcher): Promise[A] = new DefaultPromise[A]()

  /**
   * Creates an already completed Promise with the specified exception
   */
  def failed[T](exception: Throwable)(implicit dispatcher: MessageDispatcher): Promise[T] = new KeptPromise[T](Left(exception))

  /**
   * Creates an already completed Promise with the specified result
   */
  def successful[T](result: T)(implicit dispatcher: MessageDispatcher): Promise[T] = new KeptPromise[T](Right(result))
}

/**
 * Essentially this is the Promise (or write-side) of a Future (read-side).
 */
trait Promise[T] extends Future[T] {

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
    other onComplete { f ⇒ complete(f.value.get) }
    this
  }

  final def <<(value: T): Future[T] @cps[Future[Any]] = shift { cont: (Future[T] ⇒ Future[Any]) ⇒ cont(complete(Right(value))) }

  final def <<(other: Future[T]): Future[T] @cps[Future[Any]] = shift { cont: (Future[T] ⇒ Future[Any]) ⇒
    val fr = Promise[Any]()
    this completeWith other onComplete { f ⇒
      try {
        fr completeWith cont(f)
      } catch {
        case e: Exception ⇒
          dispatcher.prerequisites.eventStream.publish(Error(e, "Promise.completeWith", e.getMessage))
          fr failure e
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
          dispatcher.prerequisites.eventStream.publish(Error(e, "Promise.completeWith", e.getMessage))
          fr failure e
      }
    }
    fr
  }
}

//Companion object to FState, just to provide a cheap, immutable default entry
private[dispatch] object DefaultPromise {
  def EmptyPending[T](): FState[T] = emptyPendingValue.asInstanceOf[FState[T]]

  /**
   * Represents the internal state of the DefaultCompletableFuture
   */

  sealed trait FState[+T] { def value: Option[Either[Throwable, T]] }
  case class Pending[T](listeners: List[Future[T] ⇒ Unit] = Nil) extends FState[T] {
    def value: Option[Either[Throwable, T]] = None
  }
  case class Success[T](value: Option[Either[Throwable, T]] = None) extends FState[T] {
    def result: T = value.get.right.get
  }
  case class Failure[T](value: Option[Either[Throwable, T]] = None) extends FState[T] {
    def exception: Throwable = value.get.left.get
  }
  private val emptyPendingValue = Pending[Nothing](Nil)
}

/**
 * The default concrete Future implementation.
 */
class DefaultPromise[T](implicit val dispatcher: MessageDispatcher) extends AbstractPromise with Promise[T] {
  self ⇒

  import DefaultPromise.{ FState, Success, Failure, Pending }

  protected final def tryAwait(atMost: Duration): Boolean = {
    Future.blocking

    @tailrec
    def awaitUnsafe(waitTimeNanos: Long): Boolean = {
      if (value.isEmpty && waitTimeNanos > 0) {
        val ms = NANOSECONDS.toMillis(waitTimeNanos)
        val ns = (waitTimeNanos % 1000000l).toInt //As per object.wait spec
        val start = System.nanoTime()
        try { synchronized { if (value.isEmpty) wait(ms, ns) } } catch { case e: InterruptedException ⇒ }

        awaitUnsafe(waitTimeNanos - (System.nanoTime() - start))
      } else
        value.isDefined
    }
    awaitUnsafe(if (atMost.isFinite) atMost.toNanos else Long.MaxValue)
  }

  def ready(atMost: Duration)(implicit permit: CanAwait): this.type =
    if (value.isDefined || tryAwait(atMost)) this
    else throw new TimeoutException("Futures timed out after [" + atMost.toMillis + "] milliseconds")

  def result(atMost: Duration)(implicit permit: CanAwait): T =
    ready(atMost).value.get match {
      case Left(e)  ⇒ throw e
      case Right(r) ⇒ r
    }

  def value: Option[Either[Throwable, T]] = getState.value

  @inline
  private[this] final def updater = AbstractPromise.updater.asInstanceOf[AtomicReferenceFieldUpdater[AbstractPromise, FState[T]]]

  @inline
  protected final def updateState(oldState: FState[T], newState: FState[T]): Boolean = updater.compareAndSet(this, oldState, newState)

  @inline
  protected final def getState: FState[T] = updater.get(this)

  def tryComplete(value: Either[Throwable, T]): Boolean = {
    val callbacks: List[Future[T] ⇒ Unit] = {
      try {
        @tailrec
        def tryComplete: List[Future[T] ⇒ Unit] = {
          val cur = getState

          cur match {
            case Pending(listeners) ⇒
              if (updateState(cur, if (value.isLeft) Failure(Some(value)) else Success(Some(value)))) listeners
              else tryComplete
            case _ ⇒ null
          }
        }
        tryComplete
      } finally {
        synchronized { notifyAll() } //Notify any evil blockers
      }
    }

    callbacks match {
      case null             ⇒ false
      case cs if cs.isEmpty ⇒ true
      case cs               ⇒ Future.dispatchTask(() ⇒ cs foreach notifyCompleted); true
    }
  }

  def onComplete(func: Future[T] ⇒ Unit): this.type = {
    @tailrec //Returns whether the future has already been completed or not
    def tryAddCallback(): Boolean = {
      val cur = getState
      cur match {
        case _: Success[_] | _: Failure[_] ⇒ true
        case p: Pending[_] ⇒
          val pt = p.asInstanceOf[Pending[T]]
          if (updateState(pt, pt.copy(listeners = func :: pt.listeners))) false else tryAddCallback()
      }
    }

    if (tryAddCallback()) Future.dispatchTask(() ⇒ notifyCompleted(func))

    this
  }

  private def notifyCompleted(func: Future[T] ⇒ Unit) {
    // TODO FIXME catching all and continue isn't good for OOME, ticket #1418
    try { func(this) } catch { case e ⇒ dispatcher.prerequisites.eventStream.publish(Error(e, "Future", "Future onComplete-callback raised an exception")) }
  }
}

/**
 * An already completed Future is seeded with it's result at creation, is useful for when you are participating in
 * a Future-composition but you already have a value to contribute.
 */
final class KeptPromise[T](suppliedValue: Either[Throwable, T])(implicit val dispatcher: MessageDispatcher) extends Promise[T] {
  val value = Some(suppliedValue)

  def tryComplete(value: Either[Throwable, T]): Boolean = true
  def onComplete(func: Future[T] ⇒ Unit): this.type = {
    Future dispatchTask (() ⇒ func(this))
    this
  }

  def ready(atMost: Duration)(implicit permit: CanAwait): this.type = this
  def result(atMost: Duration)(implicit permit: CanAwait): T = value.get match {
    case Left(e)  ⇒ throw e
    case Right(r) ⇒ r
  }
}
