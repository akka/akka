/*
 * Copyright (c) 2011 Miles Sabin 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.shapeless

import poly._

/**
 * Heterogenous map with type-level key/value associations that are fixed by an arbitrary
 * relation `R`.
 *
 * `HMap`s extend `Poly` and hence are also polymorphic function values with type-specific
 * cases corresponding to the map's type-level key/value associations.
 */
class HMap[R[_, _]](underlying: Map[Any, Any] = Map.empty) extends Poly1 {
  def get[K, V](k: K)(implicit ev: R[K, V]): Option[V] = underlying.get(k).asInstanceOf[Option[V]]

  def +[K, V](kv: (K, V))(implicit ev: R[K, V]): HMap[R] = new HMap[R](underlying + kv)
  def -[K](k: K): HMap[R] = new HMap[R](underlying - k)

  implicit def caseRel[K, V](implicit ev: R[K, V]) = Case1[this.type, K, V](get(_).get)
}

object HMap {
  def apply[R[_, _]] = new HMapBuilder[R]

  def empty[R[_, _]] = new HMap[R]
  def empty[R[_, _]](underlying: Map[Any, Any]) = new HMap[R](underlying)
}

/**
 * Type class witnessing the existence of a natural transformation between `K[_]` and `V[_]`.
 *
 * Use this trait to represent an `HMap` relation of the form `K[T]` maps to `V[T]`.
 *
 * @author Miles Sabin
 */
class ~?>[K[_], V[_]] {
  class λ[K, V]
}

object ~?> {
  implicit def rel[K[_], V[_]]: K ~?> V = new (K ~?> V)

  implicit def witness[K[_], V[_], T](implicit rel: K ~?> V): rel.λ[K[T], V[T]] = new rel.λ[K[T], V[T]]
}

