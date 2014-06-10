
/*
 * Copyright (c) 2011-14 Miles Sabin
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

trait TupleTypeableInstances {
  import syntax.typeable._

  implicit def tuple1Typeable[A](implicit castA: Typeable[A]) = new Typeable[Tuple1[A]] {
    def cast(t: Any): Option[Tuple1[A]] = {
      if (t == null) Some(t.asInstanceOf[Tuple1[A]])
      else if (t.isInstanceOf[Tuple1[_]]) {
        val p = t.asInstanceOf[Tuple1[_]]
        for (_ ← p._1.cast[A])
          yield t.asInstanceOf[Tuple1[A]]
      } else None
    }
  }

  implicit def tuple2Typeable[A, B](implicit castA: Typeable[A], castB: Typeable[B]) = new Typeable[(A, B)] {
    def cast(t: Any): Option[(A, B)] = {
      if (t == null) Some(t.asInstanceOf[(A, B)])
      else if (t.isInstanceOf[(_, _)]) {
        val p = t.asInstanceOf[(_, _)]
        for (_ ← p._1.cast[A]; _ ← p._2.cast[B])
          yield t.asInstanceOf[(A, B)]
      } else None
    }
  }

  implicit def tuple3Typeable[A, B, C](implicit castA: Typeable[A], castB: Typeable[B], castC: Typeable[C]) = new Typeable[(A, B, C)] {
    def cast(t: Any): Option[(A, B, C)] = {
      if (t == null) Some(t.asInstanceOf[(A, B, C)])
      else if (t.isInstanceOf[(_, _, _)]) {
        val p = t.asInstanceOf[(_, _, _)]
        for (_ ← p._1.cast[A]; _ ← p._2.cast[B]; _ ← p._3.cast[C])
          yield t.asInstanceOf[(A, B, C)]
      } else None
    }
  }

  implicit def tuple4Typeable[A, B, C, D](implicit castA: Typeable[A], castB: Typeable[B], castC: Typeable[C], castD: Typeable[D]) = new Typeable[(A, B, C, D)] {
    def cast(t: Any): Option[(A, B, C, D)] = {
      if (t == null) Some(t.asInstanceOf[(A, B, C, D)])
      else if (t.isInstanceOf[(_, _, _, _)]) {
        val p = t.asInstanceOf[(_, _, _, _)]
        for (_ ← p._1.cast[A]; _ ← p._2.cast[B]; _ ← p._3.cast[C]; _ ← p._4.cast[D])
          yield t.asInstanceOf[(A, B, C, D)]
      } else None
    }
  }

  implicit def tuple5Typeable[A, B, C, D, E](implicit castA: Typeable[A], castB: Typeable[B], castC: Typeable[C], castD: Typeable[D], castE: Typeable[E]) = new Typeable[(A, B, C, D, E)] {
    def cast(t: Any): Option[(A, B, C, D, E)] = {
      if (t == null) Some(t.asInstanceOf[(A, B, C, D, E)])
      else if (t.isInstanceOf[(_, _, _, _, _)]) {
        val p = t.asInstanceOf[(_, _, _, _, _)]
        for (_ ← p._1.cast[A]; _ ← p._2.cast[B]; _ ← p._3.cast[C]; _ ← p._4.cast[D]; _ ← p._5.cast[E])
          yield t.asInstanceOf[(A, B, C, D, E)]
      } else None
    }
  }

  implicit def tuple6Typeable[A, B, C, D, E, F](implicit castA: Typeable[A], castB: Typeable[B], castC: Typeable[C], castD: Typeable[D], castE: Typeable[E], castF: Typeable[F]) = new Typeable[(A, B, C, D, E, F)] {
    def cast(t: Any): Option[(A, B, C, D, E, F)] = {
      if (t == null) Some(t.asInstanceOf[(A, B, C, D, E, F)])
      else if (t.isInstanceOf[(_, _, _, _, _, _)]) {
        val p = t.asInstanceOf[(_, _, _, _, _, _)]
        for (_ ← p._1.cast[A]; _ ← p._2.cast[B]; _ ← p._3.cast[C]; _ ← p._4.cast[D]; _ ← p._5.cast[E]; _ ← p._6.cast[F])
          yield t.asInstanceOf[(A, B, C, D, E, F)]
      } else None
    }
  }

  implicit def tuple7Typeable[A, B, C, D, E, F, G](implicit castA: Typeable[A], castB: Typeable[B], castC: Typeable[C], castD: Typeable[D], castE: Typeable[E], castF: Typeable[F], castG: Typeable[G]) = new Typeable[(A, B, C, D, E, F, G)] {
    def cast(t: Any): Option[(A, B, C, D, E, F, G)] = {
      if (t == null) Some(t.asInstanceOf[(A, B, C, D, E, F, G)])
      else if (t.isInstanceOf[(_, _, _, _, _, _, _)]) {
        val p = t.asInstanceOf[(_, _, _, _, _, _, _)]
        for (_ ← p._1.cast[A]; _ ← p._2.cast[B]; _ ← p._3.cast[C]; _ ← p._4.cast[D]; _ ← p._5.cast[E]; _ ← p._6.cast[F]; _ ← p._7.cast[G])
          yield t.asInstanceOf[(A, B, C, D, E, F, G)]
      } else None
    }
  }

  implicit def tuple8Typeable[A, B, C, D, E, F, G, H](implicit castA: Typeable[A], castB: Typeable[B], castC: Typeable[C], castD: Typeable[D], castE: Typeable[E], castF: Typeable[F], castG: Typeable[G], castH: Typeable[H]) = new Typeable[(A, B, C, D, E, F, G, H)] {
    def cast(t: Any): Option[(A, B, C, D, E, F, G, H)] = {
      if (t == null) Some(t.asInstanceOf[(A, B, C, D, E, F, G, H)])
      else if (t.isInstanceOf[(_, _, _, _, _, _, _, _)]) {
        val p = t.asInstanceOf[(_, _, _, _, _, _, _, _)]
        for (_ ← p._1.cast[A]; _ ← p._2.cast[B]; _ ← p._3.cast[C]; _ ← p._4.cast[D]; _ ← p._5.cast[E]; _ ← p._6.cast[F]; _ ← p._7.cast[G]; _ ← p._8.cast[H])
          yield t.asInstanceOf[(A, B, C, D, E, F, G, H)]
      } else None
    }
  }

  implicit def tuple9Typeable[A, B, C, D, E, F, G, H, I](implicit castA: Typeable[A], castB: Typeable[B], castC: Typeable[C], castD: Typeable[D], castE: Typeable[E], castF: Typeable[F], castG: Typeable[G], castH: Typeable[H], castI: Typeable[I]) = new Typeable[(A, B, C, D, E, F, G, H, I)] {
    def cast(t: Any): Option[(A, B, C, D, E, F, G, H, I)] = {
      if (t == null) Some(t.asInstanceOf[(A, B, C, D, E, F, G, H, I)])
      else if (t.isInstanceOf[(_, _, _, _, _, _, _, _, _)]) {
        val p = t.asInstanceOf[(_, _, _, _, _, _, _, _, _)]
        for (_ ← p._1.cast[A]; _ ← p._2.cast[B]; _ ← p._3.cast[C]; _ ← p._4.cast[D]; _ ← p._5.cast[E]; _ ← p._6.cast[F]; _ ← p._7.cast[G]; _ ← p._8.cast[H]; _ ← p._9.cast[I])
          yield t.asInstanceOf[(A, B, C, D, E, F, G, H, I)]
      } else None
    }
  }

  implicit def tuple10Typeable[A, B, C, D, E, F, G, H, I, J](implicit castA: Typeable[A], castB: Typeable[B], castC: Typeable[C], castD: Typeable[D], castE: Typeable[E], castF: Typeable[F], castG: Typeable[G], castH: Typeable[H], castI: Typeable[I], castJ: Typeable[J]) = new Typeable[(A, B, C, D, E, F, G, H, I, J)] {
    def cast(t: Any): Option[(A, B, C, D, E, F, G, H, I, J)] = {
      if (t == null) Some(t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J)])
      else if (t.isInstanceOf[(_, _, _, _, _, _, _, _, _, _)]) {
        val p = t.asInstanceOf[(_, _, _, _, _, _, _, _, _, _)]
        for (_ ← p._1.cast[A]; _ ← p._2.cast[B]; _ ← p._3.cast[C]; _ ← p._4.cast[D]; _ ← p._5.cast[E]; _ ← p._6.cast[F]; _ ← p._7.cast[G]; _ ← p._8.cast[H]; _ ← p._9.cast[I]; _ ← p._10.cast[J])
          yield t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J)]
      } else None
    }
  }

  implicit def tuple11Typeable[A, B, C, D, E, F, G, H, I, J, K](implicit castA: Typeable[A], castB: Typeable[B], castC: Typeable[C], castD: Typeable[D], castE: Typeable[E], castF: Typeable[F], castG: Typeable[G], castH: Typeable[H], castI: Typeable[I], castJ: Typeable[J], castK: Typeable[K]) = new Typeable[(A, B, C, D, E, F, G, H, I, J, K)] {
    def cast(t: Any): Option[(A, B, C, D, E, F, G, H, I, J, K)] = {
      if (t == null) Some(t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K)])
      else if (t.isInstanceOf[(_, _, _, _, _, _, _, _, _, _, _)]) {
        val p = t.asInstanceOf[(_, _, _, _, _, _, _, _, _, _, _)]
        for (_ ← p._1.cast[A]; _ ← p._2.cast[B]; _ ← p._3.cast[C]; _ ← p._4.cast[D]; _ ← p._5.cast[E]; _ ← p._6.cast[F]; _ ← p._7.cast[G]; _ ← p._8.cast[H]; _ ← p._9.cast[I]; _ ← p._10.cast[J]; _ ← p._11.cast[K])
          yield t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K)]
      } else None
    }
  }

  implicit def tuple12Typeable[A, B, C, D, E, F, G, H, I, J, K, L](implicit castA: Typeable[A], castB: Typeable[B], castC: Typeable[C], castD: Typeable[D], castE: Typeable[E], castF: Typeable[F], castG: Typeable[G], castH: Typeable[H], castI: Typeable[I], castJ: Typeable[J], castK: Typeable[K], castL: Typeable[L]) = new Typeable[(A, B, C, D, E, F, G, H, I, J, K, L)] {
    def cast(t: Any): Option[(A, B, C, D, E, F, G, H, I, J, K, L)] = {
      if (t == null) Some(t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L)])
      else if (t.isInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _)]) {
        val p = t.asInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _)]
        for (_ ← p._1.cast[A]; _ ← p._2.cast[B]; _ ← p._3.cast[C]; _ ← p._4.cast[D]; _ ← p._5.cast[E]; _ ← p._6.cast[F]; _ ← p._7.cast[G]; _ ← p._8.cast[H]; _ ← p._9.cast[I]; _ ← p._10.cast[J]; _ ← p._11.cast[K]; _ ← p._12.cast[L])
          yield t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L)]
      } else None
    }
  }

  implicit def tuple13Typeable[A, B, C, D, E, F, G, H, I, J, K, L, M](implicit castA: Typeable[A], castB: Typeable[B], castC: Typeable[C], castD: Typeable[D], castE: Typeable[E], castF: Typeable[F], castG: Typeable[G], castH: Typeable[H], castI: Typeable[I], castJ: Typeable[J], castK: Typeable[K], castL: Typeable[L], castM: Typeable[M]) = new Typeable[(A, B, C, D, E, F, G, H, I, J, K, L, M)] {
    def cast(t: Any): Option[(A, B, C, D, E, F, G, H, I, J, K, L, M)] = {
      if (t == null) Some(t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L, M)])
      else if (t.isInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _, _)]) {
        val p = t.asInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _, _)]
        for (_ ← p._1.cast[A]; _ ← p._2.cast[B]; _ ← p._3.cast[C]; _ ← p._4.cast[D]; _ ← p._5.cast[E]; _ ← p._6.cast[F]; _ ← p._7.cast[G]; _ ← p._8.cast[H]; _ ← p._9.cast[I]; _ ← p._10.cast[J]; _ ← p._11.cast[K]; _ ← p._12.cast[L]; _ ← p._13.cast[M])
          yield t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L, M)]
      } else None
    }
  }

  implicit def tuple14Typeable[A, B, C, D, E, F, G, H, I, J, K, L, M, N](implicit castA: Typeable[A], castB: Typeable[B], castC: Typeable[C], castD: Typeable[D], castE: Typeable[E], castF: Typeable[F], castG: Typeable[G], castH: Typeable[H], castI: Typeable[I], castJ: Typeable[J], castK: Typeable[K], castL: Typeable[L], castM: Typeable[M], castN: Typeable[N]) = new Typeable[(A, B, C, D, E, F, G, H, I, J, K, L, M, N)] {
    def cast(t: Any): Option[(A, B, C, D, E, F, G, H, I, J, K, L, M, N)] = {
      if (t == null) Some(t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L, M, N)])
      else if (t.isInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _, _, _)]) {
        val p = t.asInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _, _, _)]
        for (_ ← p._1.cast[A]; _ ← p._2.cast[B]; _ ← p._3.cast[C]; _ ← p._4.cast[D]; _ ← p._5.cast[E]; _ ← p._6.cast[F]; _ ← p._7.cast[G]; _ ← p._8.cast[H]; _ ← p._9.cast[I]; _ ← p._10.cast[J]; _ ← p._11.cast[K]; _ ← p._12.cast[L]; _ ← p._13.cast[M]; _ ← p._14.cast[N])
          yield t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L, M, N)]
      } else None
    }
  }

  implicit def tuple15Typeable[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O](implicit castA: Typeable[A], castB: Typeable[B], castC: Typeable[C], castD: Typeable[D], castE: Typeable[E], castF: Typeable[F], castG: Typeable[G], castH: Typeable[H], castI: Typeable[I], castJ: Typeable[J], castK: Typeable[K], castL: Typeable[L], castM: Typeable[M], castN: Typeable[N], castO: Typeable[O]) = new Typeable[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O)] {
    def cast(t: Any): Option[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O)] = {
      if (t == null) Some(t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O)])
      else if (t.isInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _)]) {
        val p = t.asInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _)]
        for (_ ← p._1.cast[A]; _ ← p._2.cast[B]; _ ← p._3.cast[C]; _ ← p._4.cast[D]; _ ← p._5.cast[E]; _ ← p._6.cast[F]; _ ← p._7.cast[G]; _ ← p._8.cast[H]; _ ← p._9.cast[I]; _ ← p._10.cast[J]; _ ← p._11.cast[K]; _ ← p._12.cast[L]; _ ← p._13.cast[M]; _ ← p._14.cast[N]; _ ← p._15.cast[O])
          yield t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O)]
      } else None
    }
  }

  implicit def tuple16Typeable[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P](implicit castA: Typeable[A], castB: Typeable[B], castC: Typeable[C], castD: Typeable[D], castE: Typeable[E], castF: Typeable[F], castG: Typeable[G], castH: Typeable[H], castI: Typeable[I], castJ: Typeable[J], castK: Typeable[K], castL: Typeable[L], castM: Typeable[M], castN: Typeable[N], castO: Typeable[O], castP: Typeable[P]) = new Typeable[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P)] {
    def cast(t: Any): Option[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P)] = {
      if (t == null) Some(t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P)])
      else if (t.isInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)]) {
        val p = t.asInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)]
        for (_ ← p._1.cast[A]; _ ← p._2.cast[B]; _ ← p._3.cast[C]; _ ← p._4.cast[D]; _ ← p._5.cast[E]; _ ← p._6.cast[F]; _ ← p._7.cast[G]; _ ← p._8.cast[H]; _ ← p._9.cast[I]; _ ← p._10.cast[J]; _ ← p._11.cast[K]; _ ← p._12.cast[L]; _ ← p._13.cast[M]; _ ← p._14.cast[N]; _ ← p._15.cast[O]; _ ← p._16.cast[P])
          yield t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P)]
      } else None
    }
  }

  implicit def tuple17Typeable[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q](implicit castA: Typeable[A], castB: Typeable[B], castC: Typeable[C], castD: Typeable[D], castE: Typeable[E], castF: Typeable[F], castG: Typeable[G], castH: Typeable[H], castI: Typeable[I], castJ: Typeable[J], castK: Typeable[K], castL: Typeable[L], castM: Typeable[M], castN: Typeable[N], castO: Typeable[O], castP: Typeable[P], castQ: Typeable[Q]) = new Typeable[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q)] {
    def cast(t: Any): Option[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q)] = {
      if (t == null) Some(t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q)])
      else if (t.isInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)]) {
        val p = t.asInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)]
        for (_ ← p._1.cast[A]; _ ← p._2.cast[B]; _ ← p._3.cast[C]; _ ← p._4.cast[D]; _ ← p._5.cast[E]; _ ← p._6.cast[F]; _ ← p._7.cast[G]; _ ← p._8.cast[H]; _ ← p._9.cast[I]; _ ← p._10.cast[J]; _ ← p._11.cast[K]; _ ← p._12.cast[L]; _ ← p._13.cast[M]; _ ← p._14.cast[N]; _ ← p._15.cast[O]; _ ← p._16.cast[P]; _ ← p._17.cast[Q])
          yield t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q)]
      } else None
    }
  }

  implicit def tuple18Typeable[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R](implicit castA: Typeable[A], castB: Typeable[B], castC: Typeable[C], castD: Typeable[D], castE: Typeable[E], castF: Typeable[F], castG: Typeable[G], castH: Typeable[H], castI: Typeable[I], castJ: Typeable[J], castK: Typeable[K], castL: Typeable[L], castM: Typeable[M], castN: Typeable[N], castO: Typeable[O], castP: Typeable[P], castQ: Typeable[Q], castR: Typeable[R]) = new Typeable[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R)] {
    def cast(t: Any): Option[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R)] = {
      if (t == null) Some(t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R)])
      else if (t.isInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)]) {
        val p = t.asInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)]
        for (_ ← p._1.cast[A]; _ ← p._2.cast[B]; _ ← p._3.cast[C]; _ ← p._4.cast[D]; _ ← p._5.cast[E]; _ ← p._6.cast[F]; _ ← p._7.cast[G]; _ ← p._8.cast[H]; _ ← p._9.cast[I]; _ ← p._10.cast[J]; _ ← p._11.cast[K]; _ ← p._12.cast[L]; _ ← p._13.cast[M]; _ ← p._14.cast[N]; _ ← p._15.cast[O]; _ ← p._16.cast[P]; _ ← p._17.cast[Q]; _ ← p._18.cast[R])
          yield t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R)]
      } else None
    }
  }

  implicit def tuple19Typeable[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S](implicit castA: Typeable[A], castB: Typeable[B], castC: Typeable[C], castD: Typeable[D], castE: Typeable[E], castF: Typeable[F], castG: Typeable[G], castH: Typeable[H], castI: Typeable[I], castJ: Typeable[J], castK: Typeable[K], castL: Typeable[L], castM: Typeable[M], castN: Typeable[N], castO: Typeable[O], castP: Typeable[P], castQ: Typeable[Q], castR: Typeable[R], castS: Typeable[S]) = new Typeable[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S)] {
    def cast(t: Any): Option[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S)] = {
      if (t == null) Some(t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S)])
      else if (t.isInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)]) {
        val p = t.asInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)]
        for (_ ← p._1.cast[A]; _ ← p._2.cast[B]; _ ← p._3.cast[C]; _ ← p._4.cast[D]; _ ← p._5.cast[E]; _ ← p._6.cast[F]; _ ← p._7.cast[G]; _ ← p._8.cast[H]; _ ← p._9.cast[I]; _ ← p._10.cast[J]; _ ← p._11.cast[K]; _ ← p._12.cast[L]; _ ← p._13.cast[M]; _ ← p._14.cast[N]; _ ← p._15.cast[O]; _ ← p._16.cast[P]; _ ← p._17.cast[Q]; _ ← p._18.cast[R]; _ ← p._19.cast[S])
          yield t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S)]
      } else None
    }
  }

  implicit def tuple20Typeable[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T](implicit castA: Typeable[A], castB: Typeable[B], castC: Typeable[C], castD: Typeable[D], castE: Typeable[E], castF: Typeable[F], castG: Typeable[G], castH: Typeable[H], castI: Typeable[I], castJ: Typeable[J], castK: Typeable[K], castL: Typeable[L], castM: Typeable[M], castN: Typeable[N], castO: Typeable[O], castP: Typeable[P], castQ: Typeable[Q], castR: Typeable[R], castS: Typeable[S], castT: Typeable[T]) = new Typeable[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T)] {
    def cast(t: Any): Option[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T)] = {
      if (t == null) Some(t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T)])
      else if (t.isInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)]) {
        val p = t.asInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)]
        for (_ ← p._1.cast[A]; _ ← p._2.cast[B]; _ ← p._3.cast[C]; _ ← p._4.cast[D]; _ ← p._5.cast[E]; _ ← p._6.cast[F]; _ ← p._7.cast[G]; _ ← p._8.cast[H]; _ ← p._9.cast[I]; _ ← p._10.cast[J]; _ ← p._11.cast[K]; _ ← p._12.cast[L]; _ ← p._13.cast[M]; _ ← p._14.cast[N]; _ ← p._15.cast[O]; _ ← p._16.cast[P]; _ ← p._17.cast[Q]; _ ← p._18.cast[R]; _ ← p._19.cast[S]; _ ← p._20.cast[T])
          yield t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T)]
      } else None
    }
  }

  implicit def tuple21Typeable[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U](implicit castA: Typeable[A], castB: Typeable[B], castC: Typeable[C], castD: Typeable[D], castE: Typeable[E], castF: Typeable[F], castG: Typeable[G], castH: Typeable[H], castI: Typeable[I], castJ: Typeable[J], castK: Typeable[K], castL: Typeable[L], castM: Typeable[M], castN: Typeable[N], castO: Typeable[O], castP: Typeable[P], castQ: Typeable[Q], castR: Typeable[R], castS: Typeable[S], castT: Typeable[T], castU: Typeable[U]) = new Typeable[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U)] {
    def cast(t: Any): Option[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U)] = {
      if (t == null) Some(t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U)])
      else if (t.isInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)]) {
        val p = t.asInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)]
        for (_ ← p._1.cast[A]; _ ← p._2.cast[B]; _ ← p._3.cast[C]; _ ← p._4.cast[D]; _ ← p._5.cast[E]; _ ← p._6.cast[F]; _ ← p._7.cast[G]; _ ← p._8.cast[H]; _ ← p._9.cast[I]; _ ← p._10.cast[J]; _ ← p._11.cast[K]; _ ← p._12.cast[L]; _ ← p._13.cast[M]; _ ← p._14.cast[N]; _ ← p._15.cast[O]; _ ← p._16.cast[P]; _ ← p._17.cast[Q]; _ ← p._18.cast[R]; _ ← p._19.cast[S]; _ ← p._20.cast[T]; _ ← p._21.cast[U])
          yield t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U)]
      } else None
    }
  }

  implicit def tuple22Typeable[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V](implicit castA: Typeable[A], castB: Typeable[B], castC: Typeable[C], castD: Typeable[D], castE: Typeable[E], castF: Typeable[F], castG: Typeable[G], castH: Typeable[H], castI: Typeable[I], castJ: Typeable[J], castK: Typeable[K], castL: Typeable[L], castM: Typeable[M], castN: Typeable[N], castO: Typeable[O], castP: Typeable[P], castQ: Typeable[Q], castR: Typeable[R], castS: Typeable[S], castT: Typeable[T], castU: Typeable[U], castV: Typeable[V]) = new Typeable[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V)] {
    def cast(t: Any): Option[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V)] = {
      if (t == null) Some(t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V)])
      else if (t.isInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)]) {
        val p = t.asInstanceOf[(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)]
        for (_ ← p._1.cast[A]; _ ← p._2.cast[B]; _ ← p._3.cast[C]; _ ← p._4.cast[D]; _ ← p._5.cast[E]; _ ← p._6.cast[F]; _ ← p._7.cast[G]; _ ← p._8.cast[H]; _ ← p._9.cast[I]; _ ← p._10.cast[J]; _ ← p._11.cast[K]; _ ← p._12.cast[L]; _ ← p._13.cast[M]; _ ← p._14.cast[N]; _ ← p._15.cast[O]; _ ← p._16.cast[P]; _ ← p._17.cast[Q]; _ ← p._18.cast[R]; _ ← p._19.cast[S]; _ ← p._20.cast[T]; _ ← p._21.cast[U]; _ ← p._22.cast[V])
          yield t.asInstanceOf[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V)]
      } else None
    }
  }

}