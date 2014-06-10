/*
 * Copyright (c) 2011-13 Miles Sabin 
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

import poly._

/**
 * An implementation of [http://research.microsoft.com/en-us/um/people/simonpj/papers/hmap/
 * "Scrap your boilerplate with class"] in Scala.
 *
 * @author Miles Sabin
 */

/**
 * Type class representing one-level generic queries.
 */
trait Data[F, T, R] {
  def gmapQ(t: T): List[R]
}

trait LowPriorityData {
  /**
   * Default Data type class instance.
   */
  implicit def dfltData[F, T, R]: Data[F, T, R] = new Data[F, T, R] {
    def gmapQ(t: T): List[R] = Nil
  }

  /**
   * Data type class instance for types with associated `Generic`s.
   *
   * The use of a macro here is essential to support resolution of recursive references.
   */
  implicit def genericData[F <: Poly, T, R, U](implicit gen: Generic.Aux[T, R]): Data[F, T, U] = macro DataMacros.genericDataImpl[F, T, R, U]
}

object Data extends LowPriorityData {
  def gmapQ[F, T, R](f: F)(t: T)(implicit data: Data[F, T, R]) = data.gmapQ(t)

  /**
   * Data type class instance for `List`s.
   */
  implicit def listData[F <: Poly, T, R](implicit qt: Case1.Aux[F, T, R]): Data[F, List[T], R] = new Data[F, List[T], R] {
    def gmapQ(t: List[T]) = t.map(qt)
  }

  /**
   * Data type class instance for `HList`s.
   */
  implicit def hnilData[F <: Poly, R]: Data[F, HNil, R] =
    new Data[F, HNil, R] {
      def gmapQ(t: HNil) = Nil
    }

  // Use of macro here is solely to prevent spurious implicit divergence
  implicit def hlistData[F <: Poly, H, T <: HList, R](implicit qh: Case1.Aux[F, H, R], ct: Data[F, T, R]): Data[F, H :: T, R] = macro DataMacros.hlistDataImpl[F, H, T, R]

  /**
   * Data type class instance for `Coproducts`s.
   */
  implicit def cnilData[F <: Poly, R]: Data[F, CNil, R] =
    new Data[F, CNil, R] {
      def gmapQ(t: CNil) = Nil
    }

  // Use of macro here is solely to prevent spurious implicit divergence
  implicit def coproductData[F <: Poly, H, T <: Coproduct, R](implicit qh: Case1.Aux[F, H, R], ct: Data[F, T, R]): Data[F, H :+: T, R] = macro DataMacros.coproductDataImpl[F, H, T, R]
}

object DataMacros {
  def genericDataImpl[F: c.WeakTypeTag, T: c.WeakTypeTag, R: c.WeakTypeTag, U: c.WeakTypeTag](c: Context)(gen: c.Expr[Generic.Aux[T, R]]): c.Expr[Data[F, T, U]] = {
    import c.universe._
    import Flag._

    val hlistSym = c.mirror.staticClass("shapeless.HList")
    val hlistTpe = hlistSym.asClass.toType

    val coproductSym = c.mirror.staticClass("shapeless.Coproduct")
    val coproductTpe = coproductSym.asClass.toType

    val fTpe = weakTypeOf[F]
    val tTpe = weakTypeOf[T]
    val rTpe = weakTypeOf[R]
    val uTpe = weakTypeOf[U]

    if (tTpe <:< hlistTpe || tTpe <:< coproductTpe) {
      c.abort(c.enclosingPosition, "HLists and Coproducts not handled here")
    }

    val dataSym = c.mirror.staticClass("shapeless.Data")

    val pendingSuperCall = Apply(Select(Super(This(tpnme.EMPTY), tpnme.EMPTY), nme.CONSTRUCTOR), List())

    val thisDataTypeTree =
      AppliedTypeTree(
        Ident(dataSym),
        List(TypeTree(fTpe), TypeTree(tTpe), TypeTree(uTpe)))

    val reprDataTypeTree =
      AppliedTypeTree(
        Ident(dataSym),
        List(TypeTree(fTpe), TypeTree(rTpe), TypeTree(uTpe)))

    val recName = newTermName(c.fresh)
    val className = newTypeName(c.fresh)
    val genericName = newTermName(c.fresh)
    val reprDataName = newTermName(c.fresh)

    val recClass =
      ClassDef(Modifiers(FINAL), className, List(),
        Template(
          List(thisDataTypeTree),
          emptyValDef,
          List(
            // Implicit publication of this to tie the knot
            ValDef(Modifiers(IMPLICIT), recName, thisDataTypeTree, This(tpnme.EMPTY)),

            DefDef(
              Modifiers(), nme.CONSTRUCTOR, List(),
              List(List()),
              TypeTree(),
              Block(List(pendingSuperCall), Literal(Constant(())))),

            DefDef(
              Modifiers(), newTermName("gmapQ"), List(),
              List(List(ValDef(Modifiers(PARAM), newTermName("t"), TypeTree(tTpe), EmptyTree))),
              TypeTree(),
              Block(
                List(
                  ValDef(Modifiers(), genericName, TypeTree(), gen.tree),
                  // Resolve the Data instance for the representation here, within the
                  // scope of the implicit self-publication above, allowing successful
                  // resolution of recursive references
                  ValDef(Modifiers(), reprDataName, reprDataTypeTree,
                    TypeApply(
                      Select(Ident(definitions.PredefModule), newTermName("implicitly")),
                      List(reprDataTypeTree)))),
                Apply(
                  Select(Ident(reprDataName), newTermName("gmapQ")),
                  List(
                    Apply(
                      Select(Ident(genericName), newTermName("to")),
                      List(
                        Ident(newTermName("t")))))))))))

    val block =
      Block(
        List(recClass),
        Apply(Select(New(Ident(className)), nme.CONSTRUCTOR), List()))

    c.Expr[Data[F, T, U]](block)
  }

  def hlistDataImpl[F: c.WeakTypeTag, H: c.WeakTypeTag, T <: HList: c.WeakTypeTag, R: c.WeakTypeTag](c: Context)(qh: c.Expr[Case1.Aux[F, H, R]], ct: c.Expr[Data[F, T, R]]): c.Expr[Data[F, H :: T, R]] = {
    import c.universe._

    reify {
      new Data[F, H :: T, R] {
        val qhs = qh.splice
        val cts = ct.splice
        def gmapQ(t: H :: T) = qhs(t.head :: HNil) :: cts.gmapQ(t.tail)
      }
    }
  }

  def coproductDataImpl[F: c.WeakTypeTag, H: c.WeakTypeTag, T <: Coproduct: c.WeakTypeTag, R: c.WeakTypeTag](c: Context)(qh: c.Expr[Case1.Aux[F, H, R]], ct: c.Expr[Data[F, T, R]]): c.Expr[Data[F, H :+: T, R]] = {
    import c.universe._

    reify {
      new Data[F, H :+: T, R] {
        val qhs = qh.splice
        val cts = ct.splice
        def gmapQ(c: H :+: T) = c match {
          case Inl(h) ⇒ List(qhs(h :: HNil))
          case Inr(t) ⇒ cts.gmapQ(t)
        }
      }
    }
  }
}

/**
 * Type class representing one-level generic transformations.
 */
trait DataT[F, T, U] {
  def gmapT(t: T): U
}

trait LowPriorityDataT {
  /**
   * Default DataT type class instance.
   */
  implicit def dfltDataT[F, T, U](implicit ev: T <:< U): DataT[F, T, U] = new DataT[F, T, U] {
    def gmapT(t: T) = t
  }

  /**
   * DataT type class instance for type with associated `Generics`s.
   *
   * The use of a macro here is essential to support resolution of recursive references.
   */
  implicit def genericDataT[F <: Poly, T, R](implicit gen: Generic.Aux[T, R]): DataT[F, T, T] = macro DataTMacros.genericDataTImpl[F, T, R]
}

object DataT extends LowPriorityDataT {
  def gmapT[F, T, U](f: F)(t: T)(implicit data: DataT[F, T, U]) = data.gmapT(t)

  /**
   * DataT type class instance for `List`s.
   */
  implicit def listDataT[F <: Poly, T, U](implicit ft: Case1.Aux[F, T, U]): DataT[F, List[T], List[U]] =
    new DataT[F, List[T], List[U]] {
      def gmapT(t: List[T]) = t.map(ft)
    }

  /**
   * DataT type class instance for `HList`s.
   */
  implicit def hnilDataT[F <: Poly]: DataT[F, HNil, HNil] =
    new DataT[F, HNil, HNil] {
      def gmapT(t: HNil) = HNil
    }

  // Use of macro here is solely to prevent spurious implicit divergence
  implicit def hlistDataT[F <: Poly, H, T <: HList, U, V <: HList](implicit fh: Case1.Aux[F, H, U], ct: DataT[F, T, V]): DataT[F, H :: T, U :: V] = macro DataTMacros.hlistDataTImpl[F, H, T, U, V]

  /**
   * DataT type class instance for `Coproducts`s.
   */
  implicit def cnilDataT[F <: Poly]: DataT[F, CNil, CNil] =
    new DataT[F, CNil, CNil] {
      def gmapT(t: CNil) = sys.error("CNil is equivelant to Nothing there should be no values of this type")
    }

  // Use of macro here is solely to prevent spurious implicit divergence
  implicit def coproductDataT[F <: Poly, H, T <: Coproduct, U, V <: Coproduct](implicit fh: Case1.Aux[F, H, U], ct: DataT[F, T, V]): DataT[F, H :+: T, U :+: V] = macro DataTMacros.coproductDataTImpl[F, H, T, U, V]
}

object DataTMacros {
  def genericDataTImpl[F: c.WeakTypeTag, T: c.WeakTypeTag, R: c.WeakTypeTag](c: Context)(gen: c.Expr[Generic.Aux[T, R]]): c.Expr[DataT[F, T, T]] = {
    import c.universe._
    import Flag._

    val hlistSym = c.mirror.staticClass("shapeless.HList")
    val hlistTpe = hlistSym.asClass.toType

    val coproductSym = c.mirror.staticClass("shapeless.Coproduct")
    val coproductTpe = coproductSym.asClass.toType

    val fTpe = weakTypeOf[F]
    val tTpe = weakTypeOf[T]
    val rTpe = weakTypeOf[R]

    if (tTpe <:< hlistTpe || tTpe <:< coproductTpe) {
      c.abort(c.enclosingPosition, "HLists and Coproducts not handled here")
    }

    val dataTSym = c.mirror.staticClass("shapeless.DataT")

    val pendingSuperCall = Apply(Select(Super(This(tpnme.EMPTY), tpnme.EMPTY), nme.CONSTRUCTOR), List())

    val thisDataTTypeTree =
      AppliedTypeTree(
        Ident(dataTSym),
        List(TypeTree(fTpe), TypeTree(tTpe), TypeTree(tTpe)))

    val reprDataTTypeTree =
      AppliedTypeTree(
        Ident(dataTSym),
        List(TypeTree(fTpe), TypeTree(rTpe), TypeTree(rTpe)))

    val recName = newTermName(c.fresh)
    val className = newTypeName(c.fresh)
    val genericName = newTermName(c.fresh)
    val reprDataTName = newTermName(c.fresh)

    val recClass =
      ClassDef(Modifiers(FINAL), className, List(),
        Template(
          List(thisDataTTypeTree),
          emptyValDef,
          List(
            // Implicit publication of this to tie the knot
            ValDef(Modifiers(IMPLICIT), recName, thisDataTTypeTree, This(tpnme.EMPTY)),

            DefDef(
              Modifiers(), nme.CONSTRUCTOR, List(),
              List(List()),
              TypeTree(),
              Block(List(pendingSuperCall), Literal(Constant(())))),

            DefDef(
              Modifiers(), newTermName("gmapT"), List(),
              List(List(ValDef(Modifiers(PARAM), newTermName("t"), TypeTree(tTpe), EmptyTree))),
              TypeTree(),
              Block(
                List(
                  ValDef(Modifiers(), genericName, TypeTree(), gen.tree),
                  // Resolve the DataT instance for the representation here, within the
                  // scope of the implicit self-publication above, allowing successful
                  // resolution of recursive references
                  ValDef(Modifiers(), reprDataTName, reprDataTTypeTree,
                    TypeApply(
                      Select(Ident(definitions.PredefModule), newTermName("implicitly")),
                      List(reprDataTTypeTree)))),
                Apply(
                  Select(Ident(genericName), newTermName("from")),
                  List(
                    Apply(
                      Select(Ident(reprDataTName), newTermName("gmapT")),
                      List(
                        Apply(
                          Select(Ident(genericName), newTermName("to")),
                          List(
                            Ident(newTermName("t")))))))))))))

    val block =
      Block(
        List(recClass),
        Apply(Select(New(Ident(className)), nme.CONSTRUCTOR), List()))

    c.Expr[DataT[F, T, T]](block)
  }

  def hlistDataTImpl[F: c.WeakTypeTag, H: c.WeakTypeTag, T <: HList: c.WeakTypeTag, U: c.WeakTypeTag, V <: HList: c.WeakTypeTag](c: Context)(fh: c.Expr[Case1.Aux[F, H, U]], ct: c.Expr[DataT[F, T, V]]): c.Expr[DataT[F, H :: T, U :: V]] = {
    import c.universe._

    reify {
      new DataT[F, H :: T, U :: V] {
        val fhs = fh.splice
        val cts = ct.splice
        def gmapT(t: H :: T): U :: V = fhs(t.head :: HNil) :: cts.gmapT(t.tail)
      }
    }
  }

  def coproductDataTImpl[F: c.WeakTypeTag, H: c.WeakTypeTag, T <: Coproduct: c.WeakTypeTag, U: c.WeakTypeTag, V <: Coproduct: c.WeakTypeTag](c: Context)(fh: c.Expr[Case1.Aux[F, H, U]], ct: c.Expr[DataT[F, T, V]]): c.Expr[DataT[F, H :+: T, U :+: V]] = {
    import c.universe._

    reify {
      new DataT[F, H :+: T, U :+: V] {
        val fhs = fh.splice
        val cts = ct.splice
        def gmapT(c: H :+: T) = c match {
          case Inl(h) ⇒ Inl(fhs(h :: HNil))
          case Inr(t) ⇒ Inr(cts.gmapT(t))
        }
      }
    }
  }
}

class EverythingAux[F, K] extends Poly

trait LowPriorityEverythingAux {
  implicit def generic[E, F <: Poly, K <: Poly, T, G, R](implicit unpack: Unpack2[E, EverythingAux, F, K], f: Case1.Aux[F, T, R], gen: Generic.Aux[T, G], data: Data[E, G, R], k: Case2.Aux[K, R, R, R]) =
    Case1[E, T, R](t ⇒ data.gmapQ(gen.to(t)).foldLeft(f(t))(k))
}

object EverythingAux extends LowPriorityEverythingAux {
  implicit def default[E, F <: Poly, K <: Poly, T, R](implicit unpack: Unpack2[E, EverythingAux, F, K], f: Case1.Aux[F, T, R], data: Data[E, T, R], k: Case2.Aux[K, R, R, R]) =
    Case1[E, T, R](t ⇒ data.gmapQ(t).foldLeft(f(t))(k))
}

class EverywhereAux[F] extends Poly

trait LowPriorityEverywhereAux {
  implicit def generic[E, F <: Poly, T, G](implicit unpack: Unpack1[E, EverywhereAux, F], gen: Generic.Aux[T, G], data: DataT[E, G, G], f: Case1[F, T] = Case1[F, T, T](identity)): Case1[E, T] { type Result = f.Result } =
    Case1[E, T, f.Result](t ⇒ f(gen.from(data.gmapT(gen.to(t)))))
}

object EverywhereAux extends LowPriorityEverywhereAux {
  implicit def default[E, F <: Poly, T, U](implicit unpack: Unpack1[E, EverywhereAux, F], data: DataT[E, T, U], f: Case1[F, U] = Case1[F, U, U](identity)): Case1[E, T] { type Result = f.Result } =
    Case1[E, T, f.Result](t ⇒ f(data.gmapT(t)))
}
