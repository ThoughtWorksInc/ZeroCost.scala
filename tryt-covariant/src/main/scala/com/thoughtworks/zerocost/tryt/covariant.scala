package com.thoughtworks.zerocost.tryt

import scala.language.higherKinds
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}
import cats._
import com.thoughtworks.zerocost.LiftIO
import com.thoughtworks.zerocost.LiftIO.IO
import com.thoughtworks.zerocost.parallel.covariant.Parallel

private[tryt] sealed abstract class CovariantTryTInstances3 { this: covariant.type =>

  /** @group Type classes */
  implicit final def covariantTryTParallelMonadError[F[+ _]](
      implicit F0: Monad[Parallel[F, ?]],
      E0: Semigroup[Throwable]): MonadError[Parallel[TryT[F, `+?`], ?], Throwable] = {
    Parallel.liftTypeClass[MonadError[?[_], Throwable], TryT[F, `+?`]](new TryTMonadError[F] with TryTParallelApply[F] {
      override implicit def E: Semigroup[Throwable] = E0
      implicit override def F: Monad[F] = Parallel.unliftTypeClass(F0)
    })
  }
}

private[tryt] sealed abstract class CovariantTryTInstances2 extends CovariantTryTInstances3 { this: covariant.type =>

  /** @group Type classes */
  implicit final def covariantTryTParallelLiftIO[F[+ _]](
      implicit F0: LiftIO[Parallel[F, ?]]): LiftIO[Parallel[TryT[F, `+?`], ?]] =
    Parallel.liftTypeClass[LiftIO, TryT[F, `+?`]](new TryTLiftIO[F] {
      implicit override def F: LiftIO[F] = Parallel.unliftTypeClass(F0)
    })

  /** @group Type classes */
  implicit final def covariantTryTParallelApply[F[+ _]](implicit F0: Apply[Parallel[F, ?]],
                                                        E0: Semigroup[Throwable]): Apply[Parallel[TryT[F, `+?`], ?]] = {
    Parallel.liftTypeClass[Apply, TryT[F, `+?`]](new TryTParallelApply[F] {
      override implicit def E: Semigroup[Throwable] = E0
      implicit override def F: Apply[F] = Parallel.unliftTypeClass(F0)
    })
  }
}

private[tryt] sealed abstract class CovariantTryTInstances1 extends CovariantTryTInstances2 { this: covariant.type =>

  /** @group Type classes */
  implicit final def covariantTryTMonadError[F[+ _]](implicit F0: Monad[F]): MonadError[TryT[F, `+?`], Throwable] = {
    new TryTMonadError[F] {
      implicit override def F: Monad[F] = F0
    }
  }
}

private[tryt] sealed abstract class CovariantTryTInstances0 extends CovariantTryTInstances1 { this: covariant.type =>

  /** @group Type classes */
  implicit final def covariantTryTLiftIO[F[+ _]](implicit F0: LiftIO[F]): LiftIO[TryT[F, ?]] =
    new TryTLiftIO[F] {
      implicit override def F: LiftIO[F] = F0
    }

  /** @group Type classes */
  implicit final def covariantTryTFunctor[F[+ _]](implicit F0: Functor[F]): Functor[TryT[F, ?]] =
    new TryTFunctor[F] {
      implicit override def F: Functor[F] = F0
    }
}

/** The namespace that contains the covariant [[TryT]]. */
object covariant extends CovariantTryTInstances0 with Serializable {

  private[tryt] trait OpacityTypes extends Serializable {
    type TryT[F[+ _], +A]

    def toTryT[F[+ _], A](run: F[Try[A]]): TryT[F, A]
    def fromTryT[F[+ _], A](tryT: TryT[F, A]): F[Try[A]]

  }

  @inline
  @transient
  private[tryt] lazy val opacityTypes: OpacityTypes = new OpacityTypes {

    type TryT[F[+ _], +A] = F[Try[A]]

    @inline
    override final def toTryT[F[+ _], A](tryT: F[Try[A]]): TryT[F, A] = tryT

    @inline
    override final def fromTryT[F[+ _], A](tryT: TryT[F, A]): F[Try[A]] = tryT

  }

  object TryT extends Serializable {

    /** @group Converters */
    def unapply[F[+ _], A](tryT: TryT[F, A]): Some[F[Try[A]]] = Some(opacityTypes.fromTryT(tryT))

    /** @group Converters */
    def apply[F[+ _], A](tryT: F[Try[A]]): TryT[F, A] = opacityTypes.toTryT(tryT)

  }

  import opacityTypes._

  private[tryt] trait TryTLiftIO[F[+ _]] extends LiftIO[TryT[F, ?]] {
    implicit protected def F: LiftIO[F]

    override def liftIO[A](io: IO[A]): TryT[F, A] = {
      TryT(F.liftIO { () =>
        Try(io())
      })
    }
  }

  private[tryt] trait TryTFunctor[F[+ _]] extends Functor[TryT[F, ?]] {
    implicit protected def F: Functor[F]

    override def map[A, B](fa: TryT[F, A])(f: A => B): TryT[F, B] = {
      TryT(F.map(opacityTypes.fromTryT(fa)) { tryA =>
        tryA.flatMap { a =>
          Try(f(a))
        }
      })
    }
  }

  private[tryt] trait TryTMonadError[F[+ _]] extends MonadError[TryT[F, `+?`], Throwable] with TryTFunctor[F] {
    implicit protected override def F: Monad[F]

    override def flatMap[A, B](fa: TryT[F, A])(f: A => TryT[F, B]): TryT[F, B] = TryT {
      F.flatMap[Try[A], Try[B]](opacityTypes.fromTryT(fa)) {
        case Failure(e) => F.pure(Failure(e))
        case Success(value) =>
          opacityTypes.fromTryT(
            try {
              f(value)
            } catch {
              case NonFatal(e) =>
                TryT[F, B](F.pure(Failure(e)))
            }
          )
      }
    }

    override def tailRecM[A, B](a: A)(f: (A) => TryT[F, Either[A, B]]): TryT[F, B] = {

      val fTryB: F[Try[B]] = F.tailRecM[A, Try[B]](a)(a =>
        Try(f(a)) match {
          case Success(tryT) =>
            F.map(opacityTypes.fromTryT(tryT)) {
              case Failure(e) =>
                Right(Failure[B](e))
              case Success(Right(b)) =>
                Right(Success[B](b))
              case Success(Left(a)) =>
                Left(a)
            }
          case Failure(e) =>
            F.pure(Right(Failure(e)))
      })
      TryT(fTryB)
    }
    override def pure[A](a: A): TryT[F, A] = TryT(F.pure(Try(a)))

    override def raiseError[A](e: Throwable): TryT[F, A] = TryT[F, A](F.pure(Failure(e)))

    override def handleErrorWith[A](fa: TryT[F, A])(f: (Throwable) => TryT[F, A]): TryT[F, A] = TryT {
      F.flatMap(opacityTypes.fromTryT(fa)) {
        case Failure(e) =>
          opacityTypes.fromTryT(
            try {
              f(e)
            } catch {
              case NonFatal(nonFatal) => TryT[F, A](F.pure(Failure(nonFatal)))
            }
          )
        case Success(value) => F.pure(Success(value))
      }
    }

  }

  private[tryt] trait TryTParallelApply[F[+ _]] extends Apply[TryT[F, `+?`]] with TryTFunctor[F] {
    implicit protected def F: Apply[F]
    implicit protected def E: Semigroup[Throwable]

    override def ap[A, B](f: TryT[F, A => B])(fa: TryT[F, A]): TryT[F, B] = {

      val TryT(fTryAP) = fa
      val TryT(fTryABP) = f

      import cats.syntax.all._

      val fTryBP: F[Try[B]] =
        F.map2(fTryAP, fTryABP) { (tryA: Try[A], tryAB: Try[A => B]) =>
          tryA match {
            case Success(a) =>
              tryAB match {
                case Success(ab) =>
                  try {
                    Success(ab(a))
                  } catch {
                    case NonFatal(nonfatal) => Failure(nonfatal)
                  }
                case Failure(failure) => Failure(failure)
              }
            case Failure(failure) =>
              tryAB match {
                case Success(_) => Failure(failure)
                case Failure(anotherFailure) =>
                  Failure(failure |+| anotherFailure)
              }
          }
        }
      TryT(fTryBP)
    }
  }

  /** A monad transformer for exception handling.
    *
    * @see This `TryT` transfomer is similar to [[cats.data.EitherT]],
    *      except `TryT` handles exceptions thrown in callback functions passed to
    *      [[cats.Monad.map map]], [[cats.Monad.flatMap flatMap]] or [[cats.Monad.pure pure]].
    *
    * @example As a monad transformer, `TryT` should be used with another monadic data type, like [[scala.Function0]].
    *
    *          {{{
    *          import cats.instances.function._
    *          import scala.util._
    *          import com.thoughtworks.zerocost.tryt.covariant._
    *
    *          type TryName[+A] = TryT[Function0, A]
    *          }}}
    *
    *          Given a `validate` function,
    *
    *          {{{
    *          def validate(s: String): Int = s.toInt
    *          }}}
    *
    *          when creating a `TryT`-transformed [[scala.Function0]] from the `validate`,
    *
    *          {{{
    *          import cats.syntax.all._
    *          val invalidTry: TryName[Int] = TryT(() => Try(validate("invalid input")))
    *          }}}
    *
    *          then the exceptions thrown in `validate` call should be converted to a [[scala.util.Failure]];
    *
    *          {{{
    *          val TryT(failure) = invalidTry
    *
    *          failure() should be(an[Failure[_]])
    *          }}}
    *
    *          and when there is no exception thrown in `validate` call,
    *
    *          {{{
    *          val validTry: TryName[Int] = validate("42").pure[TryName]
    *          }}}
    *
    *          then the result of `validate` call should be converted to a [[scala.util.Success]];
    *
    *          {{{
    *          val TryT(success) = validTry
    *          success() should be(Success(42))
    *          }}}
    *
    *          and when the `TryT`-transformed [[scala.Function0]] is built from a `for`-comprehension,
    *
    *          {{{
    *          val invalidForComprehension: TryName[Int] = validate("42").pure[TryName].flatMap { i =>
    *            validate("invalid input").pure[TryName].map { j =>
    *              i + j
    *            }
    *          }
    *          }}}
    *
    *          then the exceptions thrown in the `for`-comprehension should be converted to a [[scala.util.Failure]];
    *
    *          {{{
    *          val TryT(failure2) = invalidTry
    *          failure2() should be(an[Failure[_]])
    *          }}}
    *
    *
    * @note This `TryT` type is an opacity alias to `F[Try[A]]`.
    *       All type classes and helper functions for this `TryT` type are defined in the companion object [[TryT$ TryT]]
    * @template
    */
  type TryT[F[+ _], +A] = opacityTypes.TryT[F, A]

}
