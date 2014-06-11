/*
 * Copyright (c) 2013-14 Lars Hupel, Miles Sabin
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

import scala.language.experimental.macros

import scala.reflect.macros.Context

/**
 * A type class abstracting over the `product` operation of type classes over
 * types of kind `*`, as well as deriving instances using an isomorphism.
 */
trait ProductTypeClass[C[_]] {
  /**
   * Given a type class instance for `H`, and a type class instance for a
   * product, produce a type class instance for the product prepended with `H`.
   */
  def product[H, T <: HList](CHead: C[H], CTail: C[T]): C[H :: T]

  /**
   * The empty product.
   */
  def emptyProduct: C[HNil]

  /**
   * Given an isomorphism between `F` and `G`, and a type class instance for `G`,
   * produce a type class instance for `F`.
   */
  def project[F, G](instance: ⇒ C[G], to: F ⇒ G, from: G ⇒ F): C[F]
}

trait ProductTypeClassCompanion[C[_]] {
  object auto {
    implicit def derive[T](implicit ev: ProductTypeClass[C]): C[T] = macro GenericMacros.deriveProductInstance[C, T]
  }

  def apply[T](implicit ev: ProductTypeClass[C]): C[T] = macro GenericMacros.deriveProductInstance[C, T]
}

/**
 * A type class abstracting over the `product` operation of type classes over
 * types of kind `*`, as well as deriving instances using an isomorphism.
 * Refines ProductTypeClass with the addition of runtime `String` labels
 * corresponding to the names of the product elements.
 */
trait LabelledProductTypeClass[C[_]] {
  /**
   * Given a type class instance for `H`, and a type class instance for a
   * product, produce a type class instance for the product prepended with `H`.
   */
  def product[H, T <: HList](name: String, CHead: C[H], CTail: C[T]): C[H :: T]

  /**
   * The empty product.
   */
  def emptyProduct: C[HNil]

  /**
   * Given an isomorphism between `F` and `G`, and a type class instance for `G`,
   * produce a type class instance for `F`.
   */
  def project[F, G](instance: ⇒ C[G], to: F ⇒ G, from: G ⇒ F): C[F]
}

trait LabelledProductTypeClassCompanion[C[_]] {
  object auto {
    implicit def derive[T](implicit ev: LabelledProductTypeClass[C]): C[T] = macro GenericMacros.deriveLabelledProductInstance[C, T]
  }

  def apply[T](implicit ev: LabelledProductTypeClass[C]): C[T] = macro GenericMacros.deriveLabelledProductInstance[C, T]
}

/**
 * A type class additinally abstracting over the `coproduct` operation of type
 * classes over types of kind `*`.
 */
trait TypeClass[C[_]] extends ProductTypeClass[C] {
  /**
   * Given two type class instances for `L` and `R`, produce a type class
   * instance for the coproduct `L :+: R`.
   */
  def coproduct[L, R <: Coproduct](CL: ⇒ C[L], CR: ⇒ C[R]): C[L :+: R]

  /**
   * The empty coproduct
   */
  def emptyCoproduct: C[CNil]
}

trait TypeClassCompanion[C[_]] {
  object auto {
    implicit def derive[T](implicit ev: TypeClass[C]): C[T] = macro GenericMacros.deriveInstance[C, T]
  }

  def apply[T](implicit ev: TypeClass[C]): C[T] = macro GenericMacros.deriveInstance[C, T]
}

/**
 * A type class additinally abstracting over the `coproduct` operation of type
 * classes over types of kind `*`.
 *
 * Name hints can be safely ignored.
 */
trait LabelledTypeClass[C[_]] extends LabelledProductTypeClass[C] {
  /**
   * Given two type class instances for `L` and `R`, produce a type class
   * instance for the coproduct `L :+: R`.
   */
  def coproduct[L, R <: Coproduct](name: String, CL: ⇒ C[L], CR: ⇒ C[R]): C[L :+: R]

  /**
   * The empty coproduct
   */
  def emptyCoproduct: C[CNil]
}

trait LabelledTypeClassCompanion[C[_]] {
  object auto {
    implicit def derive[T](implicit ev: LabelledTypeClass[C]): C[T] = macro GenericMacros.deriveLabelledInstance[C, T]
  }

  def apply[T](implicit ev: LabelledTypeClass[C]): C[T] = macro GenericMacros.deriveLabelledInstance[C, T]
}

final class DeriveConstructors

object TypeClass {
  implicit val deriveConstructors: DeriveConstructors = new DeriveConstructors()
}
