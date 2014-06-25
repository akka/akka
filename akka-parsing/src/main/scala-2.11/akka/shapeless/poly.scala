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

import language.existentials
import language.experimental.macros

import reflect.macros.Context

import HasCompat._

// Typically the contents of this object will be imported via val alias `poly` in the shapeless package object.
object PolyDefns extends Cases {
  /**
   * Type-specific case of a polymorphic function.
   *
   * @author Miles Sabin
   */
  abstract class Case[P, L <: HList] {
    type Result
    val value: L ⇒ Result

    def apply(t: L) = value(t)
    def apply()(implicit ev: HNil =:= L) = value(HNil)
    def apply[T](t: T)(implicit ev: (T :: HNil) =:= L) = value(t :: HNil)
    def apply[T, U](t: T, u: U)(implicit ev: (T :: U :: HNil) =:= L) = value(t :: u :: HNil)
  }

  object Case extends CaseInst {
    type Aux[P, L <: HList, Result0] = Case[P, L] { type Result = Result0 }
    type Hom[P, T] = Aux[P, T :: HNil, T]

    def apply[P, L <: HList, R](v: L ⇒ R): Aux[P, L, R] = new Case[P, L] {
      type Result = R
      val value = v
    }

    implicit def materializeFromValue1[P, F[_], T]: Case[P, F[T] :: HNil] = macro materializeFromValueImpl[P, F[T], T]
    implicit def materializeFromValue2[P, T]: Case[P, T :: HNil] = macro materializeFromValueImpl[P, T, T]

    def materializeFromValueImpl[P: c.WeakTypeTag, FT: c.WeakTypeTag, T: c.WeakTypeTag](c: Context): c.Expr[Case[P, FT :: HNil]] = {
      import c.universe._
      import compat._

      val pTpe = weakTypeOf[P]
      val ftTpe = weakTypeOf[FT]
      val tTpe = weakTypeOf[T]

      val recTpe = weakTypeOf[Case[P, FT :: HNil]]
      if (c.openImplicits.tail.exists(_.pt =:= recTpe))
        c.abort(c.enclosingPosition, s"Diverging implicit expansion for Case.Aux[$pTpe, $ftTpe :: HNil]")

      val value = pTpe match {
        case SingleType(_, f) ⇒ f
        case other            ⇒ c.abort(c.enclosingPosition, "Can only materialize cases from singleton values")
      }

      c.Expr[Case[P, FT :: HNil]] {
        TypeApply(Select(Ident(value), newTermName("caseUniv")), List(TypeTree(tTpe)))
      }
    }
  }

  type Case0[P] = Case[P, HNil]
  object Case0 {
    type Aux[P, T] = Case.Aux[P, HNil, T]
    def apply[P, T](v: T): Aux[P, T] = new Case[P, HNil] {
      type Result = T
      val value = (l: HNil) ⇒ v
    }
  }

  /**
   * Represents the composition of two polymorphic function values.
   *
   * @author Miles Sabin
   */
  class Compose[F, G](f: F, g: G) extends Poly

  object Compose {
    implicit def composeCase[C, F <: Poly, G <: Poly, T, U, V](implicit unpack: Unpack2[C, Compose, F, G], cG: Case1.Aux[G, T, U], cF: Case1.Aux[F, U, V]) = new Case[C, T :: HNil] {
      type Result = V
      val value = (t: T :: HNil) ⇒ cF(cG.value(t))
    }
  }

  /**
   * Base class for lifting a `Function1` to a `Poly1`
   */
  class ->[T, R](f: T ⇒ R) extends Poly1 {
    implicit def subT[U <: T] = at[U](f)
  }

  trait LowPriorityLiftFunction1 extends Poly1 {
    implicit def default[T] = at[T](_ ⇒ HNil: HNil)
  }

  /**
   * Base class for lifting a `Function1` to a `Poly1` over the universal domain, yielding an `HList` with the result as
   * its only element if the argument is in the original functions domain, `HNil` otherwise.
   */
  class >->[T, R](f: T ⇒ R) extends LowPriorityLiftFunction1 {
    implicit def subT[U <: T] = at[U](f(_) :: HNil)
  }

  trait LowPriorityLiftU extends Poly {
    implicit def default[L <: HList] = new ProductCase[L] {
      type Result = HNil
      val value = (l: L) ⇒ HNil
    }
  }

  /**
   * Base class for lifting a `Poly` to a `Poly` over the universal domain, yielding an `HList` with the result as it's
   * only element if the argument is in the original functions domain, `HNil` otherwise.
   */
  class LiftU[P <: Poly](p: P) extends LowPriorityLiftU {
    implicit def defined[L <: HList](implicit caseT: Case[P, L]) = new ProductCase[L] {
      type Result = caseT.Result :: HNil
      val value = (l: L) ⇒ caseT(l) :: HNil
    }
  }

  /**
   * Base trait for natural transformations.
   *
   * @author Miles Sabin
   */
  trait ~>[F[_], G[_]] extends Poly1 {
    def apply[T](f: F[T]): G[T]
    implicit def caseUniv[T]: Case.Aux[F[T], G[T]] = at[F[T]](apply(_))
  }

  object ~> {
    implicit def inst1[F[_], G[_], T](f: F ~> G): F[T] ⇒ G[T] = f(_)
    implicit def inst2[G[_], T](f: Id ~> G): T ⇒ G[T] = f(_)
    implicit def inst3[F[_], T](f: F ~> Id): F[T] ⇒ T = f(_)
    implicit def inst4[T](f: Id ~> Id): T ⇒ T = f[T](_) // Explicit type argument needed here to prevent recursion?
    implicit def inst5[F[_], G, T](f: F ~> Const[G]#λ): F[T] ⇒ G = f(_)
    implicit def inst6[G, T](f: Id ~> Const[G]#λ): T ⇒ G = f(_)
    implicit def inst7[F, G](f: Const[F]#λ ~> Const[G]#λ): F ⇒ G = f(_)
  }

  /** Natural transformation with a constant type constructor on the right hand side. */
  type ~>>[F[_], R] = ~>[F, Const[R]#λ]

  /** Polymorphic identity function. */
  object identity extends (Id ~> Id) {
    def apply[T](t: T) = t
  }
}

/**
 * Base trait for polymorphic values.
 *
 * @author Miles Sabin
 */
trait Poly extends PolyApply {
  import poly._

  def compose(f: Poly) = new Compose[this.type, f.type](this, f)

  def andThen(f: Poly) = new Compose[f.type, this.type](f, this)

  /** The type of the case representing this polymorphic function at argument types `L`. */
  type ProductCase[L <: HList] = Case[this.type, L]
  object ProductCase {
    /** The type of a case of this polymorphic function of the form `L => R` */
    type Aux[L <: HList, Result0] = ProductCase[L] { type Result = Result0 }

    /** The type of a case of this polymorphic function of the form `T => T` */
    type Hom[T] = Aux[T :: HNil, T]

    def apply[L <: HList, R](v: L ⇒ R) = new ProductCase[L] {
      type Result = R
      val value = v
    }
  }

  def use[T, L <: HList, R](t: T)(implicit cb: CaseBuilder[T, L, R]) = cb(t)

  trait CaseBuilder[T, L <: HList, R] {
    def apply(t: T): ProductCase.Aux[L, R]
  }

  trait LowPriorityCaseBuilder {
    implicit def valueCaseBuilder[T]: CaseBuilder[T, HNil, T] =
      new CaseBuilder[T, HNil, T] {
        def apply(t: T) = ProductCase((_: HNil) ⇒ t)
      }
  }

  object CaseBuilder extends LowPriorityCaseBuilder {
    import ops.function.FnToProduct
    implicit def fnCaseBuilder[F, H, T <: HList, Result](implicit fntp: FnToProduct.Aux[F, ((H :: T) ⇒ Result)]): CaseBuilder[F, H :: T, Result] =
      new CaseBuilder[F, H :: T, Result] {
        def apply(f: F) = ProductCase((l: H :: T) ⇒ fntp(f)(l))
      }
  }

  def caseAt[L <: HList](implicit c: ProductCase[L]) = c

  def apply[R](implicit c: ProductCase.Aux[HNil, R]): R = c()
}

/**
 * Provides implicit conversions from polymorphic function values to monomorphic function values, eg. for use as
 * arguments to ordinary higher order functions.
 *
 * @author Miles Sabin
 */
object Poly extends PolyInst {
  implicit def inst0(p: Poly)(implicit cse: p.ProductCase[HNil]): cse.Result = cse()

  implicit def apply(f: Any): Poly = macro liftFnImpl

  def liftFnImpl(c: Context)(f: c.Expr[Any]): c.Expr[Poly] = {
    import c.universe._
    import compat._
    import Flag._

    val pendingSuperCall = Apply(Select(Super(This(tpnme.EMPTY), tpnme.EMPTY), nme.CONSTRUCTOR), List())

    val moduleName = newTermName(c.fresh)

    val anySym = c.mirror.staticClass("scala.Any")
    val anyTpe = anySym.asType.toType
    val nothingSym = c.mirror.staticClass("scala.Nothing")
    val nothingTpe = nothingSym.asType.toType

    val typeOpsSym = c.mirror.staticPackage("shapeless")
    val idSym = typeOpsSym.newTypeSymbol(newTypeName("Id"))
    val constSym = typeOpsSym.newTypeSymbol(newTypeName("Const"))

    val natTSym = c.mirror.staticClass("shapeless.PolyDefns.$tilde$greater")
    val natTTpe = natTSym.asClass.toTypeConstructor

    def mkApply(fSym: Symbol, gSym: Symbol, targ: TypeName, arg: TermName, body: Tree) = {
      def mkTargRef(sym: Symbol) =
        if (sym == idSym)
          Ident(targ)
        else if (sym.asType.typeParams.isEmpty)
          Ident(sym.name)
        else
          AppliedTypeTree(Ident(sym.name), List(Ident(targ)))

      DefDef(
        Modifiers(), newTermName("apply"),
        List(TypeDef(Modifiers(PARAM), targ, List(), TypeBoundsTree(TypeTree(nothingTpe), TypeTree(anyTpe)))),
        List(List(ValDef(Modifiers(PARAM), arg, mkTargRef(fSym), EmptyTree))),
        mkTargRef(gSym),
        body)
    }

    def destructureMethod(methodSym: MethodSymbol) = {
      val paramSym = methodSym.paramss match {
        case List(List(ps)) ⇒ ps
        case _              ⇒ c.abort(c.enclosingPosition, "Expression $f has the wrong shape to be converted to a polymorphic function value")
      }

      def extractTc(tpe: Type): Symbol = {
        val owner = tpe.typeSymbol.owner
        if (owner == methodSym) idSym
        else tpe.typeConstructor.typeSymbol
      }

      (extractTc(paramSym.typeSignature), extractTc(methodSym.returnType))
    }

    def stripSymbolsAndTypes(tree: Tree, internalSyms: List[Symbol]) = {
      // Adapted from https://github.com/scala/async/blob/master/src/main/scala/scala/async/TransformUtils.scala#L226
      final class StripSymbolsAndTypes extends Transformer {
        override def transform(tree: Tree): Tree = super.transform {
          tree match {
            case TypeApply(fn, args) if args.map(t ⇒ transform(t)) exists (_.isEmpty) ⇒ transform(fn)
            case EmptyTree ⇒ tree
            case _ ⇒
              val hasSymbol: Boolean = {
                val reflectInternalTree = tree.asInstanceOf[symtab.Tree forSome { val symtab: reflect.internal.SymbolTable }]
                reflectInternalTree.hasSymbol
              }
              val dupl = tree.duplicate
              if (hasSymbol)
                dupl.symbol = NoSymbol
              dupl.tpe = null
              dupl
          }
        }
      }

      (new StripSymbolsAndTypes).transform(tree)
    }

    val (fSym, gSym, dd) =
      f.tree match {
        case Block(List(), Function(List(_), Apply(TypeApply(fun, _), _))) ⇒
          val methodSym = fun.symbol.asMethod

          val (fSym1, gSym1) = destructureMethod(methodSym)
          val body = Apply(TypeApply(Ident(methodSym), List(Ident(newTypeName("T")))), List(Ident(newTermName("t"))))

          (fSym1, gSym1, mkApply(fSym1, gSym1, newTypeName("T"), newTermName("t"), body))

        case Block(List(), Function(List(_), Apply(fun, _))) ⇒
          val methodSym = fun.symbol.asMethod

          val (fSym1, gSym1) = destructureMethod(methodSym)
          val body = Apply(Ident(methodSym), List(Ident(newTermName("t"))))

          (fSym1, gSym1, mkApply(fSym1, gSym1, newTypeName("T"), newTermName("t"), body))

        case Block(List(df @ DefDef(mods, _, List(tp), List(List(vp)), tpt, rhs)), Literal(Constant(()))) ⇒
          val methodSym = df.symbol.asMethod

          val (fSym1, gSym1) = destructureMethod(methodSym)

          val body = mkApply(fSym1, gSym1, tp.name, vp.name, stripSymbolsAndTypes(rhs, List()))

          (fSym1, gSym1, body)

        case Block(List(df @ DefDef(_, _, List(), List(List(vp)), tpt, rhs)), Literal(Constant(()))) ⇒
          val methodSym = df.symbol.asMethod

          val (fSym1, gSym1) = destructureMethod(methodSym)

          val body = mkApply(fSym1, gSym1, newTypeName("T"), vp.name, stripSymbolsAndTypes(rhs, List()))
          (fSym1, gSym1, body)

        case _ ⇒
          c.abort(c.enclosingPosition, s"Unable to convert expression $f to a polymorphic function value")
      }

    def mkTargTree(sym: Symbol) =
      if (sym == idSym)
        Select(Ident(newTermName("shapeless")), newTypeName("Id"))
      else if (sym.asType.typeParams.isEmpty)
        SelectFromTypeTree(
          AppliedTypeTree(
            Select(Ident(newTermName("shapeless")), newTypeName("Const")),
            List(Ident(sym.name))),
          newTypeName("λ"))
      else
        Ident(sym.name)

    val liftedTypeTree =
      AppliedTypeTree(
        Ident(natTSym),
        List(mkTargTree(fSym), mkTargTree(gSym)))

    val moduleDef =
      ModuleDef(Modifiers(), moduleName,
        Template(
          List(liftedTypeTree),
          emptyValDef,
          List(
            DefDef(
              Modifiers(), nme.CONSTRUCTOR, List(),
              List(List()),
              TypeTree(),
              Block(List(pendingSuperCall), Literal(Constant(())))),

            dd)))

    c.Expr[Poly] {
      Block(
        List(moduleDef),
        Ident(moduleName))
    }
  }
}

/**
 * Trait simplifying the creation of polymorphic values.
 */
trait Poly0 extends Poly {
  type Case0[T] = ProductCase.Aux[HNil, T]

  def at[T](t: T) = new ProductCase[HNil] {
    type Result = T
    val value = (l: HNil) ⇒ t
  }
}
