/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.util

import scala.collection.LinearSeq
import scala.annotation.tailrec
import scala.reflect.{ classTag, ClassTag }

/**
 * INTERNAL API
 */
private[http] abstract class EnhancedSeq[+A] {
  /**
   * Returns the first defined result of the given function when applied to the underlying sequence (in order) or
   * `None`, if the given function returns `None` for all elements of the underlying sequence.
   */
  def mapFind[B](f: A ⇒ Option[B]): Option[B]

  /**
   * Returns the first object of type B in the underlying sequence or `None`, if none is found.
   */
  /*def findByType[B: ClassTag]: Option[B] = {
    val erasure = classTag.runtimeClass
    mapFind(x ⇒ if (erasure.isInstance(x)) Some(x.asInstanceOf[B]) else None)
  }*/
}

private[http] class EnhancedLinearSeq[+A](underlying: LinearSeq[A]) extends EnhancedSeq[A] {
  def mapFind[B](f: A ⇒ Option[B]): Option[B] = {
    @tailrec def mapFind(seq: LinearSeq[A]): Option[B] =
      if (seq.nonEmpty) {
        val x = f(seq.head)
        if (x.isEmpty) mapFind(seq.tail) else x
      } else None
    mapFind(underlying)
  }
}

private[http] class EnhancedIndexedSeq[+A](underlying: IndexedSeq[A]) extends EnhancedSeq[A] {
  def mapFind[B](f: A ⇒ Option[B]): Option[B] = {
    @tailrec def mapFind(ix: Int): Option[B] =
      if (ix < underlying.length) {
        val x = f(underlying(ix))
        if (x.isEmpty) mapFind(ix + 1) else x
      } else None
    mapFind(0)
  }
}