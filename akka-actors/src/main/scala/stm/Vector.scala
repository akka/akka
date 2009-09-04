/**
 Copyright (c) 2007-2008, Rich Hickey
 All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions
 are met:

 * Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.

 * Redistributions in binary form must reproduce the above
   copyright notice, this list of conditions and the following
   disclaimer in the documentation and/or other materials provided
   with the distribution.

 * Neither the name of Clojure nor the names of its contributors
   may be used to endorse or promote products derived from this
   software without specific prior written permission.

 THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 POSSIBILITY OF SUCH DAMAGE.
 **/

package se.scalablesolutions.akka.collection

import Vector._

/**
 * A straight port of Clojure's <code>PersistentVector</code> class.
 *
 * @author Daniel Spiewak
 * @author Rich Hickey
 */
@serializable
class Vector[+T] private (val length: Int, shift: Int, root: Array[AnyRef], tail: Array[AnyRef]) extends RandomAccessSeq[T] { outer =>
  private val tailOff = length - tail.length
  
  /*
   * The design of this data structure inherantly requires heterogenous arrays.
   * It is *possible* to design around this, but the result is comparatively
   * quite inefficient.  With respect to this fact, I have left the original
   * (somewhat dynamically-typed) implementation in place.
   */
  
  private[collection] def this() = this(0, 5, EmptyArray, EmptyArray)
  
  def apply(i: Int): T = {
    if (i >= 0 && i < length) {
      if (i >= tailOff) {
        tail(i & 0x01f).asInstanceOf[T]
      } else {
        var arr = root
        var level = shift
        
        while (level > 0) {
          arr = arr((i >>> level) & 0x01f).asInstanceOf[Array[AnyRef]]
          level -= 5
        }
        
        arr(i & 0x01f).asInstanceOf[T]
      }
    } else throw new IndexOutOfBoundsException(i.toString)
  }
  
  def update[A >: T](i: Int, obj: A): Vector[A] = {
    if (i >= 0 && i < length) {
      if (i >= tailOff) {
        val newTail = new Array[AnyRef](tail.length)
        Array.copy(tail, 0, newTail, 0, tail.length)
        newTail(i & 0x01f) = obj.asInstanceOf[AnyRef]
        
        new Vector[A](length, shift, root, newTail)
      } else {
        new Vector[A](length, shift, doAssoc(shift, root, i, obj), tail)
      }
    } else if (i == length) {
      this + obj
    } else throw new IndexOutOfBoundsException(i.toString)
  }
  
  private def doAssoc[A >: T](level: Int, arr: Array[AnyRef], i: Int, obj: A): Array[AnyRef] = {
    val ret = new Array[AnyRef](arr.length)
    Array.copy(arr, 0, ret, 0, arr.length)
    
    if (level == 0) {
      ret(i & 0x01f) = obj.asInstanceOf[AnyRef]
    } else {
      val subidx = (i >>> level) & 0x01f
      ret(subidx) = doAssoc(level - 5, arr(subidx).asInstanceOf[Array[AnyRef]], i, obj)
    }
    
    ret
  }
  
  override def ++[A >: T](other: Iterable[A]) = other.foldLeft(this:Vector[A]) { _ + _ }
  
  def +[A >: T](obj: A): Vector[A] = {
    if (tail.length < 32) {
      val newTail = new Array[AnyRef](tail.length + 1)
      Array.copy(tail, 0, newTail, 0, tail.length)
      newTail(tail.length) = obj.asInstanceOf[AnyRef]
      
      new Vector[A](length + 1, shift, root, newTail)
    } else {
      var (newRoot, expansion) = pushTail(shift - 5, root, tail, null)
      var newShift = shift
      
      if (expansion != null) {
        newRoot = array(newRoot, expansion)
        newShift += 5
      }
      
      new Vector[A](length + 1, newShift, newRoot, array(obj.asInstanceOf[AnyRef]))
    }
  }
  
  private def pushTail(level: Int, arr: Array[AnyRef], tailNode: Array[AnyRef], expansion: AnyRef): (Array[AnyRef], AnyRef) = {
    val newChild = if (level == 0) tailNode else {
      val (newChild, subExpansion) = pushTail(level - 5, arr(arr.length - 1).asInstanceOf[Array[AnyRef]], tailNode, expansion)
      
      if (subExpansion == null) {
        val ret = new Array[AnyRef](arr.length)
        Array.copy(arr, 0, ret, 0, arr.length)
        
        ret(arr.length - 1) = newChild
        
        return (ret, null)
      } else subExpansion
    }
    
    // expansion
    if (arr.length == 32) {
      (arr, array(newChild)) 
    } else {
      val ret = new Array[AnyRef](arr.length + 1)
      Array.copy(arr, 0, ret, 0, arr.length)
      ret(arr.length) = newChild
      
      (ret, null)
    }
  }
  
  /**
   * Removes the <i>tail</i> element of this vector.
   */
  def pop: Vector[T] = {
    if (length == 0) {
      throw new IllegalStateException("Can't pop empty vector")
    } else if (length == 1) {
      EmptyVector
    } else if (tail.length > 1) {
      val newTail = new Array[AnyRef](tail.length - 1)
      Array.copy(tail, 0, newTail, 0, newTail.length)
      
      new Vector[T](length - 1, shift, root, newTail)
    } else {
      var (newRoot, pTail) = popTail(shift - 5, root, null)
      var newShift = shift
      
      if (newRoot == null) {
        newRoot = EmptyArray
      }
      
      if (shift > 5 && newRoot.length == 1) {
        newRoot = newRoot(0).asInstanceOf[Array[AnyRef]]
        newShift -= 5
      }
      
      new Vector[T](length - 1, newShift, newRoot, pTail.asInstanceOf[Array[AnyRef]])
    }
  }
  
  private def popTail(shift: Int, arr: Array[AnyRef], pTail: AnyRef): (Array[AnyRef], AnyRef) = {
    val newPTail = if (shift > 0) {
      val (newChild, subPTail) = popTail(shift - 5, arr(arr.length - 1).asInstanceOf[Array[AnyRef]], pTail)
      
      if (newChild != null) {
        val ret = new Array[AnyRef](arr.length)
        Array.copy(arr, 0, ret, 0, arr.length)
        
        ret(arr.length - 1) = newChild
        
        return (ret, subPTail)
      }
      subPTail
    } else if (shift == 0) {
      arr(arr.length - 1)
    } else pTail
    
    // contraction
    if (arr.length == 1) {
      (null, newPTail)
    } else {    
      val ret = new Array[AnyRef](arr.length - 1)
      Array.copy(arr, 0, ret, 0, ret.length)
      
      (ret, newPTail)
    }
  }
  
  override def filter(p: (T)=>Boolean) = {
    var back = new Vector[T]
    var i = 0
    
    while (i < length) {
      val e = apply(i)
      if (p(e)) back += e
      
      i += 1
    }
    
    back
  }
  
  override def flatMap[A](f: (T)=>Iterable[A]):  Vector[A] = {
    var back = new Vector[A]
    var i = 0
    
    while (i < length) {
      f(apply(i)) foreach { back += _ }
      i += 1
    }
    
    back
  }
  
  override def map[A](f: (T)=>A): Vector[A] = {
    var back = new Vector[A]
    var i = 0
    
    while (i < length) {
      back += f(apply(i))
      i += 1
    }
    
    back
  }
  
  override def reverse: Vector[T] = new VectorProjection[T] {
    override val length = outer.length
    
    override def apply(i: Int) = outer.apply(length - i - 1)
  }
  
  override def subseq(from: Int, end: Int) = subVector(from, end)
  
  def subVector(from: Int, end: Int): Vector[T] = {
    if (from < 0) {
      throw new IndexOutOfBoundsException(from.toString)
    } else if (end >= length) {
      throw new IndexOutOfBoundsException(end.toString)
    } else if (end <= from) {
      throw new IllegalArgumentException("Invalid range: " + from + ".." + end)
    } else {
      new VectorProjection[T] {
        override val length = end - from
        
        override def apply(i: Int) = outer.apply(i + from)
      }
    }
  }
  
  def zip[A](that: Vector[A]) = {
    var back = new Vector[(T, A)]
    var i = 0
    
    val limit = Math.min(length, that.length)
    while (i < limit) {
      back += (apply(i), that(i))
      i += 1
    }
    
    back
  }
  
  def zipWithIndex = {
    var back = new Vector[(T, Int)]
    var i = 0
    
    while (i < length) {
      back += (apply(i), i)
      i += 1
    }
    
    back
  }
  
  override def equals(other: Any) = other match {
    case vec: Vector[T] => {
      var back = length == vec.length
      var i = 0

      while (i < length) {
        back &&= apply(i) == vec.apply(i)
        i += 1
      }

      back
    }

    case _ => false
  }

  override def hashCode = foldLeft(0) { _ ^ _.hashCode }
}

object Vector {
  private[collection] val EmptyArray = new Array[AnyRef](0)
  
  def apply[T](elems: T*) = elems.foldLeft(EmptyVector:Vector[T]) { _ + _ }
  
  def unapplySeq[T](vec: Vector[T]): Option[Seq[T]] = Some(vec)
  
  @inline
  private[collection] def array(elems: AnyRef*) = {
    val back = new Array[AnyRef](elems.length)
    Array.copy(elems, 0, back, 0, back.length)

    back
  }
}

object EmptyVector extends Vector[Nothing]

private[collection] abstract class VectorProjection[+T] extends Vector[T] {
  override val length: Int
  override def apply(i: Int): T
  
  override def +[A >: T](e: A) = innerCopy + e
  
  override def update[A >: T](i: Int, e: A) = {
    if (i < 0) {
      throw new IndexOutOfBoundsException(i.toString)
    } else if (i > length) {
      throw new IndexOutOfBoundsException(i.toString)
    } else innerCopy(i) = e
  }
  
  private lazy val innerCopy = foldLeft(EmptyVector:Vector[T]) { _ + _ }
}

