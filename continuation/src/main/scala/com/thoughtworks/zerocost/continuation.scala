package com.thoughtworks.zerocost

import com.thoughtworks.zerocost.parallel.covariant._
import scala.concurrent.{ExecutionContext, Future, Promise, SyncVar}
import cats.{Applicative, Monad}
import scala.language.higherKinds
import scala.language.existentials
import scala.util.control.TailCalls
import scala.util.control.TailCalls.TailRec

/** The name space that contains [[continuation.Continuation]] and utilities for `Continuation`.
  * @author 杨博 (Yang Bo)
  */
object continuation {

  @inline
  private def suspendTailRec[R](a: => TailRec[R]) = TailCalls.tailcall(TailCalls.done(a).result)

  private[continuation] trait OpacityTypes {
    type Continuation[R, +A]
    type ParallelContinuation[R, A] = Parallel[Continuation[R, +?], A]

    def toFunction[R, A](continuation: Continuation[R, A]): (A => TailRec[R]) => TailRec[R]
    def fromFunction[R, A](continuation: (A => TailRec[R]) => TailRec[R]): Continuation[R, A]
  }

  private[continuation] sealed trait ParallelZipState[A, B]

  private[continuation] object ParallelZipState {

    private[continuation] final case class GotNeither[A, B]() extends ParallelZipState[A, B]

    private[continuation] final case class GotA[A, B](a: A) extends ParallelZipState[A, B]

    private[continuation] final case class GotB[A, B](b: B) extends ParallelZipState[A, B]

  }

  @inline
  private[continuation] val opacityTypes: OpacityTypes = new OpacityTypes {
    type Continuation[R, +A] = (A => TailRec[R]) => TailRec[R]

    def toFunction[R, A](continuation: Continuation[R, A]): (A => TailRec[R]) => TailRec[R] = continuation
    def fromFunction[R, A](continuation: (A => TailRec[R]) => TailRec[R]): Continuation[R, A] = continuation
  }

  /** The stack-safe and covariant version of Continuations.
    * @note The underlying type of this `Continuation` is `(A => TailRec[R]) => TailRec[R]`.
    * @see [[ContinuationOps]] for extension methods for this `Continuation`.
    * @see [[UnitContinuation]] if you want to use this `Continuation` as an asynchronous task.
    * @template
    */
  type Continuation[R, +A] = opacityTypes.Continuation[R, A]

  /** A [[Continuation]] whose response type is [[scala.Unit]].
    *
    * This `UnitContinuation` type can be used as an asynchronous task.
    *
    * @see [[UnitContinuationOps]] for extension methods for this `UnitContinuationOps`.
    * @see [[ParallelContinuation]] for parallel version of this `UnitContinuation`.
    * @note This `UnitContinuation` type does not support exception handling.
    * @see [[com.thoughtworks.zerocost.future.Future Future]] for asynchronous task that supports exception handling.
    * @template
    */
  type UnitContinuation[+A] = Continuation[Unit, A]

  /** Extension methods for [[Continuation]]
    * @group Implicit Views
    */
  implicit final class ContinuationOps[R, A](val underlying: Continuation[R, A]) extends AnyVal {

    /** Runs the [[underlying]] continuation.
      *
      * @param continue the callback function that will be called once the [[underlying]] continuation complete.
      * @note The JVM call stack will grow if there are recursive calls to [[onComplete]] in `continue`.
      *       A `StackOverflowError` may occurs if the recursive calls are very deep.
      * @see [[safeOnComplete]] in case of `StackOverflowError`.
      *
      */
    @inline
    def onComplete(continue: A => R): R = {
      opacityTypes
        .toFunction(underlying) { a =>
          TailCalls.tailcall(TailCalls.done(continue(a)))
        }
        .result
    }

    /** Runs the [[underlying]] continuation like [[onComplete]], except this `safeOnComplete` is stack-safe. */
    @inline
    def safeOnComplete(continue: A => TailRec[R]): TailRec[R] = {
      Continuation.safeOnComplete(underlying)(continue)
    }

    @inline
    def reset(implicit aAsR: A <:< R): R = {
      onComplete(aAsR)
    }

  }

  /** Extension methods for [[UnitContinuation]]
    * @group Implicit Views
    */
  implicit final class UnitContinuationOps[A](val underlying: UnitContinuation[A]) extends AnyVal {

    /** Returns a memoized [[scala.concurrent.Future]] for the [[underlying]] [[UnitContinuation]].*/
    def toScalaFuture: Future[A] = {
      val promise = Promise[A]
      ContinuationOps[Unit, A](underlying).onComplete { a =>
        val _ = promise.success(a)
      }
      promise.future
    }

    /** Blocking waits and returns the result value of the [[underlying]] [[UnitContinuation]].*/
    def blockingAwait(): A = {
      val box: SyncVar[A] = new SyncVar
      underlying.onComplete { (a: A) =>
        box.put(a)
      }
      box.take
    }
  }

  /** [[com.thoughtworks.zerocost.parallel.covariant.Parallel Parallel]]-tagged type of [[UnitContinuation]] that needs to be executed in parallel when using an [[cats.Applicative]] instance
    *
    * @example Given two [[ParallelContinuation]]s that contain immediate values,
    *
    *          {{{
    *          import com.thoughtworks.zerocost.parallel.covariant._
    *          import com.thoughtworks.zerocost.continuation._
    *
    *          val pc0: ParallelContinuation[Int] = Parallel(Continuation.pure[Unit, Int](40))
    *          val pc1: ParallelContinuation[Int] = Parallel(Continuation.pure[Unit, Int](2))
    *          }}}
    *
    *          when map them together,
    *
    *          {{{
    *          val result: ParallelContinuation[Int] = continuationParallelApplicative.map2(pc0, pc1)(_ + _)
    *          }}}
    *
    *          then the result should be a `ParallelContinuation` as well,
    *          and it is able to convert to a normal [[Continuation]]
    *
    *          {{{
    *          val Parallel(contResult) = result
    *          continuationMonad.map(contResult) {
    *            _ should be(42)
    *          }.toScalaFuture
    *          }}}
    *
    * @example Given two [[ParallelContinuation]]s,
    *          each of them modifies a `var`,
    *
    *          {{{
    *          import com.thoughtworks.zerocost.parallel.covariant._
    *          import com.thoughtworks.zerocost.continuation._
    *
    *          var count0 = 0
    *          var count1 = 0
    *
    *          val pc0: ParallelContinuation[Unit] = Parallel(Continuation.delay {
    *            count0 += 1
    *          })
    *          val pc1: ParallelContinuation[Unit] = Parallel(Continuation.delay {
    *            count1 += 1
    *          })
    *          }}}
    *
    *          when map them together,
    *
    *          {{{
    *          val result: ParallelContinuation[Unit] = continuationParallelApplicative.map2(pc0, pc1){ (u0: Unit, u1: Unit) => }
    *          }}}
    *
    *          then the two vars have not been modified right now,
    *
    *          {{{
    *          count0 should be(0)
    *          count1 should be(0)
    *          }}}
    *
    *          when the result `ParallelContinuation` get done,
    *          then two vars should be modified only once for each.
    *
    *          {{{
    *          val Parallel(contResult) = result
    *          continuationMonad.map(contResult) { _: Unit =>
    *            count0 should be(1)
    *            count1 should be(1)
    *          }.toScalaFuture
    *          }}}
    *
    * @template
    */
  type ParallelContinuation[A] = Parallel[UnitContinuation, A]

  /**
    * @group Type class instances
    */
  implicit object continuationParallelApplicative extends Applicative[ParallelContinuation] with Serializable {
    override def pure[A](x: A): ParallelContinuation[A] = {
      Parallel(continuationMonad.pure(x))
    }
    override def ap[A, B](ff: ParallelContinuation[(A) => B])(fa: ParallelContinuation[A]): ParallelContinuation[B] = {
      val ffBox: SyncVar[(A) => B] = new SyncVar
      val faBox: SyncVar[A] = new SyncVar
      val ffWorker: UnitContinuation[Unit] = {
        val Parallel(ffCont) = ff
        continuationMonad.map(ffCont) { ffResult =>
          ffBox.put(ffResult)
        }
      }
      val faWorker: UnitContinuation[Unit] = {
        val Parallel(faCont) = fa
        continuationMonad.map(faCont) { faResult =>
          faBox.put(faResult)
        }
      }
      val resultCont: UnitContinuation[B] = UnitContinuation.delay {
        val ffResult = ffBox.take
        val faResult = faBox.take
        ffResult(faResult)
      }
      Parallel(continuationMonad.followedBy(continuationMonad.followedBy(ffWorker)(faWorker))(resultCont))
    }
  }

  object UnitContinuation {

    /** Returns a [[UnitContinuation]] of a blocking operation that will run on `executionContext`. */
    def execute[A](a: => A)(implicit executionContext: ExecutionContext): UnitContinuation[A] = {
      Continuation.async { continue: (A => Unit) =>
        executionContext.execute(new Runnable {
          override def run(): Unit = continue(a)
        })
      }
    }

    /** A synonym of [[Continuation.async]] */
    def async[A](start: (A => Unit) => Unit): UnitContinuation[A] = {
      Continuation.async(start)
    }

    /** A synonym of [[Continuation.pure]] */
    @inline
    def pure[A](a: A): UnitContinuation[A] = {
      Continuation.pure(a)
    }

    /** A synonym of [[Continuation.delay]] */
    def delay[A](a: => A): UnitContinuation[A] = {
      Continuation.delay(a)
    }

    /** A synonym of [[Continuation.safeAsync]] */
    def safeAsync[A](start: (A => TailRec[Unit]) => TailRec[Unit]): UnitContinuation[A] = {
      Continuation.safeAsync(start)
    }

    def suspend[A](continuation: => UnitContinuation[A]): UnitContinuation[A] = {
      Continuation.suspend(continuation)
    }

    @inline
    def apply[A](start: (A => TailRec[Unit]) => TailRec[Unit]): UnitContinuation[A] = {
      safeAsync(start)
    }

    /** A synonym of [[Continuation.unapply]] */
    @inline
    def unapply[A](continuation: UnitContinuation[A]): Some[(A => TailRec[Unit]) => TailRec[Unit]] = {
      Continuation.unapply[Unit, A](continuation)
    }
  }

  /** The companion object for [[Continuation]].
    *
    */
  object Continuation {

    private final case class Async[R, A](start: (A => R) => R) extends ((A => TailRec[R]) => TailRec[R]) {
      override def apply(continue: (A) => TailRec[R]): TailRec[R] = {
        TailCalls.tailcall {
          TailCalls.done {
            start { a =>
              continue(a).result
            }
          }
        }
      }
    }

    /** Returns a [[Continuation]] of an asynchronous operation.
      *
      * @see [[safeAsync]] in case of `StackOverflowError`.
      */
    def async[R, A](start: (A => R) => R): Continuation[R, A] = {
      safeAsync(Async(start))
    }

    private final case class Pure[R, A](a: A) extends ((A => TailRec[R]) => TailRec[R]) {
      override def apply(continue: (A) => TailRec[R]): TailRec[R] = continue(a)
    }

    /** Returns a [[Continuation]] whose value is always `a`. */
    @inline
    def pure[R, A](a: A): Continuation[R, A] = safeAsync(Pure(a))

    private final case class Delay[R, A](block: () => A) extends ((A => TailRec[R]) => TailRec[R]) {
      override def apply(continue: (A) => TailRec[R]): TailRec[R] = suspendTailRec(continue(block()))
    }

    /** Returns a [[Continuation]] of a blocking operation */
    @inline
    def delay[R, A](block: => A): Continuation[R, A] = safeAsync(Delay(block _))

    @inline
    private[thoughtworks] def safeOnComplete[R, A](continuation: Continuation[R, A])(
        continue: A => TailRec[R]): TailRec[R] = {
      suspendTailRec {
        opacityTypes.toFunction(continuation)(continue)
      }
    }

    /** Returns a [[Continuation]] of an asynchronous operation like [[async]] except this method is stack-safe. */
    def safeAsync[R, A](start: (A => TailRec[R]) => TailRec[R]): Continuation[R, A] = {
      opacityTypes.fromFunction[R, A](start)
    }

    final case class Suspend[R, A](continuation: () => Continuation[R, A]) extends ((A => TailRec[R]) => TailRec[R]) {
      def apply(continue: (A) => TailRec[R]): TailRec[R] = {
        continuation().safeOnComplete(continue)
      }
    }

    def suspend[R, A](continuation: => Continuation[R, A]): Continuation[R, A] = {
      safeAsync(Suspend(continuation _))
    }

    @inline
    def apply[R, A](start: (A => TailRec[R]) => TailRec[R]): Continuation[R, A] = {
      safeAsync(start)
    }

    /** Extracts the underlying [[scala.Function1]] of `continuation`
      *
      * @example This `unapply` can be used in pattern matching expression.
      *          {{{
      *          import com.thoughtworks.zerocost.continuation.Continuation
      *          val Continuation(f) = Continuation.pure[Unit, Int](42)
      *          f should be(a[Function1[_, _]])
      *          }}}
      *
      */
    @inline
    def unapply[R, A](continuation: Continuation[R, A]): Some[(A => TailRec[R]) => TailRec[R]] = {
      Some(opacityTypes.toFunction[R, A](continuation))
    }
  }

  private final case class Bind[R, A, B](fa: Continuation[R, A], f: (A) => Continuation[R, B])
      extends ((B => TailRec[R]) => TailRec[R]) {
    def apply(continue: (B) => TailRec[R]): TailRec[R] = {
      Continuation.safeOnComplete[R, A](fa) { a =>
        Continuation.safeOnComplete[R, B](f(a))(continue)
      }
    }
  }
  private final case class Map[R, A, B](fa: Continuation[R, A], f: (A) => B) extends ((B => TailRec[R]) => TailRec[R]) {
    def apply(continue: (B) => TailRec[R]): TailRec[R] = {
      Continuation.safeOnComplete(fa) { a: A =>
        suspendTailRec(continue(f(a)))
      }
    }
  }

  private final case class Join[R, A](ffa: Continuation[R, Continuation[R, A]])
      extends ((A => TailRec[R]) => TailRec[R]) {
    def apply(continue: A => TailRec[R]): TailRec[R] = {
      Continuation.safeOnComplete[R, Continuation[R, A]](ffa) { fa =>
        Continuation.safeOnComplete[R, A](fa)(continue)
      }
    }
  }

  private final case class TailrecM[R, A, B](f: (A) => Continuation[R, Either[A, B]], a: A)
      extends ((B => TailRec[R]) => TailRec[R]) {
    def apply(continue: (B) => TailRec[R]): TailRec[R] = {
      def loop(a: A): TailRec[R] = {
        Continuation.safeOnComplete(f(a)) {
          case Left(a) =>
            loop(a)
          case Right(b) =>
            suspendTailRec(continue(b))
        }
      }
      loop(a)
    }

  }

  /**
    * @group Type class instances
    */
  implicit def continuationMonad[R]: Monad[Continuation[R, +?]] = new Monad[Continuation[R, +?]] with Serializable {
    override def pure[A](x: A): Continuation[R, A] = Continuation.pure(x)
    override def flatMap[A, B](fa: Continuation[R, A])(f: (A) => Continuation[R, B]): Continuation[R, B] =
      Continuation.safeAsync(Bind(fa, f))
    override def tailRecM[A, B](a: A)(f: (A) => Continuation[R, Either[A, B]]): Continuation[R, B] =
      Continuation.safeAsync(TailrecM(f, a))
  }
}
