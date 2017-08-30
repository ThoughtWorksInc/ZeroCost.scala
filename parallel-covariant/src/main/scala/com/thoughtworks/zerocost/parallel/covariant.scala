package com.thoughtworks.zerocost.parallel

import scala.language.higherKinds

/**
  * @author 杨博 (Yang Bo)
  */
object covariant {

  private[covariant] trait OpacityTypes {
    type Parallel[F[+ _], +A]

    def toParallel[F[+ _], A](fa: F[A]): Parallel[F, A]
    def fromParallel[F[+ _], A](parallel: Parallel[F, A]): F[A]
    def liftTypeClass[M[_[_]], F[+ _]](typeClass: M[F]): M[Parallel[F, `+?`]]
  }

  private[covariant] val opacityTypes: OpacityTypes = new OpacityTypes {
    type Parallel[F[+ _], +A] = F[A]
    def toParallel[F[+ _], A](fa: F[A]): Parallel[F, A] = fa
    def fromParallel[F[+ _], A](parallel: Parallel[F, A]): F[A] = parallel
    def liftTypeClass[M[_[_]], F[+ _]](typeClass: M[F]): M[Parallel[F, `+?`]] = typeClass
  }

  type Parallel[F[+ _], +A] = opacityTypes.Parallel[F, A]
  object Parallel {

    def apply[F[+ _], A](fa: F[A]): Parallel[F, A] = opacityTypes.toParallel(fa)
    def unapply[F[+ _], A](parallel: Parallel[F, A]): Some[F[A]] = Some(opacityTypes.fromParallel[F, A](parallel))

    def liftTypeClass[M[_[_]], F[+ _]](typeClass: M[F]): M[Parallel[F, `+?`]] = opacityTypes.liftTypeClass(typeClass)

  }

}
