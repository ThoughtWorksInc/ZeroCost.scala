/*
 * Copyright 2017 ThoughtWorks, Inc.
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

package com.thoughtworks.zerocost

import scala.concurrent.ExecutionContext
import scala.language.higherKinds
import scala.util.{Failure, Success, Try}
import scala.util.control.TailCalls
import scala.util.control.TailCalls.TailRec
import cats.{Applicative, Monad, MonadError, Semigroup}
import cats.syntax.all._
import com.thoughtworks.zerocost.parallel.covariant._
import com.thoughtworks.zerocost.continuation._
import com.thoughtworks.zerocost.tryt.covariant._

/** The name space that contains [[task.Task]] and utilities for `Task`.
  *
  * == Usage ==
  *
  * Features of [[task.Task]] are provided as implicit views or type classes.
  * To enable those features, import all members under [[task]] along with Scalaz syntax.
  *
  * {{{
  * import cats.syntax.all._
  * import com.thoughtworks.zerocost.task._
  * }}}
  *
  * @author 杨博 (Yang Bo)
  */
object task {

  private trait OpacityTypes {
    type Task[+A]
    type ParallelTask[A] = Parallel[Task, A]
    def fromTryT[A](tryT: TryT[UnitContinuation, A]): Task[A]
    def toTryT[A](future: Task[A]): TryT[UnitContinuation, A]
    def taskMonadError: MonadError[Task, Throwable] with LiftIO[Task]
    def parallelTaskMonadError: MonadError[ParallelTask, Throwable] with LiftIO[ParallelTask]
  }

  private val UnitContinuationMonad = continuationInstances[Unit]

  private object TaskMonadError extends TryTMonadError[UnitContinuation] with TryTLiftIO[UnitContinuation] {
    implicit protected override def F = UnitContinuationMonad
  }

  private val ParallelContinuationMonad = parallelContinuationInstances

  private val ParallelTaskMonadError =
    Parallel.liftTypeClass[Lambda[F[_] => MonadError[F, Throwable] with LiftIO[F]], TryT[UnitContinuation, +?]](
      new TryTMonadError[UnitContinuation] with TryTLiftIO[UnitContinuation] with TryTParallelApply[UnitContinuation] {
        implicit protected override def F =
          Parallel.unliftTypeClass[Lambda[F[_] => Monad[F] with LiftIO[F]], UnitContinuation](ParallelContinuationMonad)

        override implicit protected def E: Semigroup[Throwable] = MultipleException.throwableSemigroup
      })

  private[task] val opacityTypes: OpacityTypes = new OpacityTypes {
    type Task[+A] = TryT[UnitContinuation, A]

    @inline
    override def fromTryT[A](tryT: TryT[UnitContinuation, A]): Task[A] = tryT

    @inline
    override def toTryT[A](future: Task[A]): TryT[UnitContinuation, A] = future

    def taskMonadError: MonadError[Task, Throwable] with LiftIO[Task] = TaskMonadError

    def parallelTaskMonadError: MonadError[ParallelTask, Throwable] with LiftIO[ParallelTask] = ParallelTaskMonadError
  }

  /**
    * @group Type class instances
    */
  @inline
  implicit def taskMonadError: MonadError[Task, Throwable] with LiftIO[Task] = {
    opacityTypes.taskMonadError
  }

  /**
    * @group Type class instances
    */
  @inline
  implicit def parallelTaskMonadError: MonadError[ParallelTask, Throwable] with LiftIO[ParallelTask] = {
    opacityTypes.parallelTaskMonadError
  }

  /** Extension methods for [[scala.concurrent.Future]]
    *
    * @group Implicit Views
    */
  implicit final class ScalaTaskToZeroCostTaskOps[A](scalaTask: scala.concurrent.Future[A]) {

    def toTask(implicit executionContext: ExecutionContext): Task[A] = {
      Task.async { continue =>
        scalaTask.onComplete(continue)
      }
    }
  }

  /** Extension methods for [[com.thoughtworks.zerocost.continuation.UnitContinuation UnitContinuation]]
    *
    * @group Implicit Views
    */
  implicit final class UnitContinuationToZeroCostTaskOps[A](continuation: UnitContinuation[A]) {
    def toTask: Task[A] = {
      Task(TryT(continuation.map(Try(_))))
    }
  }

  /** Extension methods for [[Task]]
    *
    * @group Implicit Views
    */
  implicit final class ZeroCostTaskOps[A](val underlying: Task[A]) extends AnyVal {
    @inline
    def toFuture: scala.concurrent.Future[A] = {
      val promise = scala.concurrent.Promise[A]
      onComplete(promise.complete)
      promise.future
    }

    /** Runs the [[underlying]] [[Task]].
      *
      * @param continue the callback function that will be called once the [[underlying]] continuation complete.
      * @note The JVM call stack will grow if there are recursive calls to [[onComplete]] in `continue`.
      *       A `StackOverflowError` may occurs if the recursive calls are very deep.
      * @see [[safeOnComplete]] in case of `StackOverflowError`.
      */
    @inline
    def onComplete(continue: Try[A] => Unit): Unit = {
      val Task(TryT(continuation)) = underlying
      continuation.onComplete(continue)
    }

    /** Runs the [[underlying]] continuation like [[onComplete]], except this `safeOnComplete` is stack-safe. */
    @inline
    def safeOnComplete(continue: Try[A] => TailRec[Unit]): TailRec[Unit] = {
      val Task(TryT(continuation)) = underlying
      continuation.safeOnComplete(continue)
    }

    /** Blocking waits and returns the result value of the [[underlying]] [[Task]].*/
    @inline
    def blockingAwait: A = {
      val Task(TryT(continuation)) = underlying
      continuation.blockingAwait.get
    }

  }

  object Task {

    /** Returns a [[Task]] of an asynchronous operation like [[async]] except this method is stack-safe. */
    def safeAsync[A](run: (Try[A] => TailRec[Unit]) => TailRec[Unit]): Task[A] = {
      fromContinuation(UnitContinuation.safeAsync(run))
    }

    /** Returns a [[Task]] of an asynchronous operation.
      *
      * @see [[safeAsync]] in case of `StackOverflowError`.
      */
    def async[A](start: (Try[A] => Unit) => Unit): Task[A] = {
      fromContinuation(UnitContinuation.async(start))
    }

    /** Returns a [[Task]] of a blocking operation that will run on `executionContext`. */
    def execute[A](a: => A)(implicit executionContext: ExecutionContext): Task[A] = {
      fromContinuation(UnitContinuation.execute(Try(a)))
    }

    /** Returns a [[Task]] whose value is always `a`. */
    @inline
    def pure[A](a: A): Task[A] = {
      fromContinuation(UnitContinuation.pure(Success(a)))
    }

    /** Returns a [[Task]] of a blocking operation */
    def delay[A](a: => A): Task[A] = {
      fromContinuation(UnitContinuation.delay(Try(a)))
    }

    /** Creates a [[Task]] from the raw [[com.thoughtworks.zerocost.tryt.covariant.TryT]] */
    @inline
    def apply[A](tryT: TryT[UnitContinuation, A]): Task[A] = {
      opacityTypes.fromTryT(tryT)
    }

    /** Extracts the underlying [[com.thoughtworks.zerocost.tryt.covariant.TryT]] of `future`
      *
      * @example This `unapply` can be used in pattern matching expression.
      *          {{{
      *          import com.thoughtworks.zerocost.task.Task
      *          import com.thoughtworks.zerocost.continuation.UnitContinuation
      *          val Task(tryT) = Task.pure[Int](42)
      *          tryT should be(a[com.thoughtworks.zerocost.tryt.covariant.TryT[UnitContinuation, _]])
      *          }}}
      *
      */
    @inline
    def unapply[A](future: Task[A]): Some[TryT[UnitContinuation, A]] = {
      Some(opacityTypes.toTryT(future))
    }

    @inline
    private def fromContinuation[A](continuation: UnitContinuation[Try[A]]): Task[A] = {
      apply(TryT[UnitContinuation, A](continuation))
    }

    def suspend[A](future: => Task[A]): Task[A] = {
      Task.safeAsync { continue =>
        future.safeOnComplete(continue)
      }
    }

  }

  /** [[com.thoughtworks.zerocost.Parallel Parallel]]-tagged type of [[Task]] that needs to be executed in parallel when using an [[cats.Applicative]] instance
    * @template
    *
    * @example Given a momoized [[Task]],
    *          {{{
    *          import com.thoughtworks.zerocost.task._
    *          val futureA: Task[String] = Task.execute("a").toFuture.toTask
    *          }}}
    *
    *          and two `ParallelTask`s that throw exceptions,
    *
    *          {{{
    *          import com.thoughtworks.zerocost.parallel.covariant.Parallel
    *          def futureB(a: String): ParallelTask[String] = Parallel(Task.execute { throw new Exception("b failed"); a + "b" })
    *          def futureC(a: String): ParallelTask[String] = Parallel(Task.execute { throw new Exception("c failed"); a + "c" })
    *          }}}
    *
    *          and a `Task` that depends on two [[scala.Predef.String String]] values.
    *
    *          {{{
    *          def futureD(b: String, c: String): Task[String] = Task.execute(b + c + "d")
    *          }}}
    *
    *          When combining those futures together,
    *
    *          {{{
    *          val futureResult = futureA.flatMap { a =>
    *            val Parallel(tupled) = futureB(a) product futureC(a)
    *            tupled.flatMap { case (b, c) =>
    *              futureD(b, c)
    *            }
    *          }
    *          }}}
    *
    *          then multiple exceptions should be handled together.
    *
    *          {{{
    *          futureResult.handleError {
    *            case MultipleException(throwables) if throwables.map(_.getMessage) == Set("b failed", "c failed") =>
    *              "Multiple exceptions handled"
    *          }.map {
    *            _ should be("Multiple exceptions handled")
    *          }.toFuture
    *          }}}
    */
  type ParallelTask[A] = Parallel[Task, A]

  /** An asynchronous task.
    *
    * @note A [[Task]] can be memoized manually
    *       by converting this [[Task]] to a [[scala.concurrent.Task]] and then converting back.
    *
    *       {{{
    *       var count = 0
    *       val notMemoized = Task.delay {
    *         count += 1
    *       }
    *       val memoized = notMemoized.toFuture.toTask;
    *       (
    *         for {
    *           _ <- memoized
    *           _ = count should be(1)
    *           _ <- memoized
    *           _ = count should be(1)
    *           _ <- memoized
    *         } yield (count should be(1))
    *       ).toFuture
    *       }}}
    *
    * @note Unlike [[scala.concurrent.Task]], this [[Task]] is not memoized by default.
    *
    *       {{{
    *       var count = 0
    *       val notMemoized = Task.delay {
    *         count += 1
    *       }
    *       count should be(0);
    *       (
    *         for {
    *           _ <- notMemoized
    *           _ = count should be(1)
    *           _ <- notMemoized
    *           _ = count should be(2)
    *           _ <- notMemoized
    *         } yield (count should be(3))
    *       ).toFuture
    *       }}}
    * @see [[ParallelTask]] for parallel version of this [[Task]].
    * @see [[ZeroCostTaskOps]] for methods available on this [[Task]].
    * @template
    */
  type Task[+A] = opacityTypes.Task[A]

}
