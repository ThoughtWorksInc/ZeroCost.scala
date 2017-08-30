package com.thoughtworks.zerocost.tryt

import scala.language.higherKinds
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}
import cats.{Applicative, Functor, Monad, MonadError, Semigroup}
import com.thoughtworks.zerocost.parallel.covariant.Parallel

private[tryt] sealed abstract class CovariantTryTInstances2 { this: covariant.type =>

  /** @group Type classes */
  implicit final def covariantTryTParallelApplicative[F[+ _]](
      implicit F0: Applicative[Parallel[F, ?]],
      S0: Semigroup[Throwable]): Applicative[Parallel[TryT[F, `+?`], ?]] = {
    new Serializable with TryTParallelApplicative[F] {
      override implicit def F: Applicative[Parallel[F, ?]] = F0
      override implicit def S: Semigroup[Throwable] = S0

    }
  }
}

private[tryt] sealed abstract class CovariantTryTInstances1 extends CovariantTryTInstances2 { this: covariant.type =>

  /** @group Type classes */
  implicit final def covariantTryTMonadError[F[+ _]](implicit F0: Monad[F]): MonadError[TryT[F, `+?`], Throwable] = {
    new Serializable with TryTMonadError[F] {
      implicit override def F: Monad[F] = F0
    }
  }
}

private[tryt] sealed abstract class CovariantTryTInstances0 extends CovariantTryTInstances1 { this: covariant.type =>

  /** @group Type classes */
  implicit final def covariantTryTFunctor[F[+ _]](implicit F0: Functor[F]): Functor[TryT[F, ?]] =
    new Serializable with TryTFunctor[F] {
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
    def apply[F[+ _], A](tryT: F[Try[A]]): TryT[F, A] = TryT(tryT)

  }

  import opacityTypes._

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

  private[tryt] trait TryTParallelApplicative[F[+ _]] extends Applicative[Parallel[TryT[F, `+?`], ?]] {
    implicit protected def F: Applicative[Parallel[F, ?]]
    implicit protected def S: Semigroup[Throwable]

    override def pure[A](a: A): Parallel[TryT[F, `+?`], A] = {
      val Parallel(trytA) = F.pure(Try(a))
      Parallel[TryT[F, `+?`], A](TryT[F, A](trytA))
    }
    override def map[A, B](pfa: Parallel[TryT[F, `+?`], A])(f: (A) => B): Parallel[TryT[F, `+?`], B] = {
      val Parallel(TryT(fa)) = pfa
      val Parallel(trytB) = F.map(Parallel[F, Try[A]](fa)) { tryA =>
        tryA.flatMap { a =>
          Try(f(a))
        }
      }
      Parallel[TryT[F, `+?`], B](TryT(trytB))
    }

    override def ap[A, B](f: Parallel[TryT[F, `+?`], A => B])(
        fa: Parallel[TryT[F, `+?`], A]): Parallel[TryT[F, `+?`], B] = {

      val fTryAP: Parallel[F, Try[A]] = try {
        val Parallel(TryT(ftryA)) = fa
        Parallel(ftryA)
      } catch {
        case NonFatal(e) =>
          F.pure(Failure(e))
      }

      val fTryABP: Parallel[F, Try[A => B]] = try {
        val Parallel(TryT(ftryF)) = f
        Parallel(ftryF)
      } catch {
        case NonFatal(e) =>
          F.pure(Failure(e))
      }

      import cats.syntax.all._

      val fTryBP: Parallel[F, Try[B]] =
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
      val Parallel(fTryB) = fTryBP
      Parallel(TryT(fTryB))
    }
  }

  /** A monad transformer for exception handling.
    *
    * @see This `TryT` transfomer is similar to [[scalaz.EitherT]],
    *      except `TryT` handles exceptions thrown in callback functions passed to
    *      [[scalaz.Monad.map map]], [[scalaz.Monad.flatMap flatMap]] or [[scalaz.Monad.pure pure]].
    *
    * @example As a monad transformer, `TryT` should be used with another monadic data type, like [[scalaz.Name]].
    *
    *          {{{
    *          import scalaz.Name
    *          import com.thoughtworks.tryt.covariant.TryT, TryT._
    *
    *          type TryName[+A] = TryT[Name, A]
    *          }}}
    *
    *          Given a `validate` function,
    *
    *          {{{
    *          def validate(s: String): Int = s.toInt
    *          }}}
    *
    *          when creating a `TryT`-transformed [[scalaz.Name]] from the `validate`,
    *
    *          {{{
    *          import scalaz.syntax.all._
    *          val invalidTry: TryName[Int] = validate("invalid input").pure[TryName]
    *          }}}
    *
    *          then the exceptions thrown in `validate` call should be converted to a [[scala.util.Failure]];
    *
    *          {{{
    *          import com.thoughtworks.tryt.covariant.TryT._
    *
    *          val TryT(Name(failure)) = invalidTry
    *
    *          import scala.util._
    *          failure should be(an[Failure[_]])
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
    *          val TryT(Name(success)) = validTry
    *          success should be(Success(42))
    *          }}}
    *
    *          and when the `TryT`-transformed [[scalaz.Name]] is built from a `for`-comprehension,
    *
    *          {{{
    *          val invalidForComprehension: TryName[Int] = for {
    *            i <- validate("42").pure[TryName]
    *            j <- validate("invalid input").pure[TryName]
    *          } yield i + j
    *          }}}
    *
    *          then the exceptions thrown in the `for`-comprehension should be converted to a [[scala.util.Failure]];
    *
    *          {{{
    *          val TryT(Name(failure2)) = invalidTry
    *          failure2 should be(an[Failure[_]])
    *          }}}
    *
    *
    * @note This `TryT` type is an opacity alias to `F[Try[A]]`.
    *       All type classes and helper functions for this `TryT` type are defined in the companion object [[TryT$ TryT]]
    * @template
    */
  type TryT[F[+ _], +A] = opacityTypes.TryT[F, A]

}
