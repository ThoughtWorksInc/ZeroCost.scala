package com.thoughtworks.zerocost

import java.util.concurrent.atomic.AtomicReference

import com.thoughtworks.zerocost.tryt._

import scala.concurrent.ExecutionContext
import scala.language.higherKinds
import scala.util.{Failure, Success, Try}
import cats.{Applicative, Monad, MonadError, Semigroup}
import com.thoughtworks.zerocost.task._
import com.thoughtworks.zerocost.continuation.{UnitContinuation, _}
import com.thoughtworks.zerocost.resourcet._
import com.thoughtworks.zerocost.parallel._

import scala.util.control.NonFatal
import cats.syntax.all._

import scala.util.control.TailCalls.TailRec

/** The namespace that contains [[Raii]].
  *
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object raii {

  /** @template */
  private[raii] type RaiiContinuation[+A] = ResourceT[UnitContinuation, A]

  private[raii] trait OpacityTypes {
    type Raii[+A]

    private[raii] def wrapToRaii[A](parallelTryT: Parallel[TryT[RaiiContinuation, +?], A]): Raii[A]

    private[raii] def unwrapRaii[A](raii: Raii[A]): Parallel[TryT[RaiiContinuation, +?], A]

    implicit private[raii] def raiiInstances: MonadError[Raii, Throwable] with LiftIO[Raii]
  }

  private val ParallelContinuationInstances = parallelContinuationInstances

  private object ParallelRaiiContinuationInstances
      extends ResourceTMonad[UnitContinuation]
      with ResourceTLiftIO[UnitContinuation]
      with ResourceTParallelApply[UnitContinuation] {
    override implicit def F =
      Parallel.unliftTypeClass[Lambda[F[_] => Monad[F] with LiftIO[F]], UnitContinuation](ParallelContinuationInstances)
  }

  private val ParallelRaiiInstances: MonadError[Parallel[TryT[RaiiContinuation, +?], ?], Throwable] with LiftIO[
    Parallel[TryT[RaiiContinuation, +?], ?]] =
    Parallel.liftTypeClass[Lambda[F[_] => MonadError[F, Throwable] with LiftIO[F]], TryT[RaiiContinuation, +?]](
      new TryTMonadError[RaiiContinuation] with TryTLiftIO[RaiiContinuation] with TryTParallelApply[RaiiContinuation] {
        override implicit def F = ParallelRaiiContinuationInstances
        override implicit protected def E: Semigroup[Throwable] = MultipleException.throwableSemigroup
      })

  /** The type-level [[http://en.cppreference.com/w/cpp/language/pimpl Pimpl]] in order to prevent the Scala compiler seeing the actual type of [[Raii]]
    *
    * @note For internal usage only.
    */
  val opacityTypes: OpacityTypes = new Serializable with OpacityTypes { outer =>
    override type Raii[+A] = Parallel[TryT[RaiiContinuation, +?], A]

    override private[raii] def wrapToRaii[A](parallelTryT: Parallel[TryT[RaiiContinuation, +?], A]): Raii[A] =
      parallelTryT

    override private[raii] def unwrapRaii[A](raii: Raii[A]): Parallel[TryT[RaiiContinuation, +?], A] = raii

    implicit private[raii] def raiiInstances: MonadError[Raii, Throwable] with LiftIO[Raii] = ParallelRaiiInstances

  }

  /** An universal monadic data type that consists of many useful monad transformers.
    *
    * == Features of `Raii` ==
    *  - [[com.thoughtworks.zerocost.tryt.TryT exception handling]]
    *  - [[com.thoughtworks.zerocost.resourcet.ResourceT automatic resource management]]
    *  - [[RaiiOps.shared reference counting]]
    *  - [[com.thoughtworks.zerocost.continuation.UnitContinuation raii programming]]
    *  - [[parallel.Parallel parallel computing]]
    *
    * @note This `Raii` type is an [[https://www.reddit.com/r/scala/comments/5qbdgq/value_types_without_anyval/dcxze9q/ opacity alias]] to `UnitContinuation[Resource[UnitContinuation, Try[A]]]`.
    * @see [[Raii$ Raii]] companion object for all type classes and helper functions for this `Raii` type.
    * @template
    */
  type Raii[+A] = opacityTypes.Raii[A]

  /** Returns an [[cats.Applicative Applicative]] type class for parallel computing.
    *
    * @note This type class requires a [[cats.Semigroup Semigroup]] to combine multiple `Throwable`s into one,
    *       in the case of multiple tasks report errors in parallel.
    * @group Type classes
    */
  implicit def raiiInstances: MonadError[Raii, Throwable] =
    opacityTypes.raiiInstances

  /** The companion object of [[Raii]]
    *
    * @define pure             Converts a strict value to a `Raii` whose [[resourcet.Resource.release release]] operation is no-op.
    * @define seenow           @see [[pure]] for strict garbage collected `Raii`
    * @define delay            Returns a non-strict `Raii` whose [[resourcet.Resource.release release]] operation is no-op.
    * @define seedelay         @see [[delay]] for non-strict garbage collected `Raii`
    * @define autocloseable    Returns a non-strict `Raii` whose [[resourcet.Resource.release release]] operation is [[java.lang.AutoCloseable.close]].
    * @define releasable       Returns a non-strict `Raii` whose [[resourcet.Resource.release release]] operation is raii.
    * @define seeautocloseable @see [[autoCloseable]] for auto-closeable `Raii`
    * @define seereleasable    @see [[monadicCloseable]] for creating a `Raii` whose [[resourcet.Resource.release release]] operation is raii.
    * @define nonstrict        Since the `Raii` is non-strict,
    *                          `A` will be recreated each time it is sequenced into a larger `Raii`.
    * @define garbageCollected `A` must be a garbage-collected type that does not hold native resource.
    */
  object Raii {
    def resource[A](resource: => Resource[UnitContinuation, A]): Raii[A] = {
      val resourceContinuation: UnitContinuation[Resource[UnitContinuation, Try[A]]] = UnitContinuation.delay {
        try {
          val Resource(a, monadicClose) = resource
          Resource(Success(a), monadicClose)
        } catch {
          case NonFatal(e) =>
            Resource(Failure(e), UnitContinuation.pure(()))
        }
      }
      Raii(Parallel(TryT(ResourceT(resourceContinuation))))
    }

    def apply[A](parallelTryT: Parallel[TryT[ResourceT[UnitContinuation, +?], +?], A]): Raii[A] = {
      opacityTypes.wrapToRaii(parallelTryT)
    }

    def unapply[A](raii: Raii[A]): Some[Parallel[TryT[ResourceT[UnitContinuation, `+?`], +?], A]] = {
      Some(opacityTypes.unwrapRaii(raii))
    }

    /** $releasable
      * $nonstrict
      * $seenow
      * $seedelay
      * $seeautocloseable
      */
    def monadicCloseable[A <: MonadicCloseable[UnitContinuation]](future: Task[A]): Raii[A] = {
      val Task(TryT(continuation)) = future
      fromContinuation(
        continuation.map {
          case failure @ Failure(e) =>
            Resource[UnitContinuation, Try[A]](
              value = Failure(e),
              release = {
                UnitContinuation.pure(())
              }
            )
          case success @ Success(releasable) =>
            Resource[UnitContinuation, Try[A]](value = Success(releasable), release = releasable.monadicClose)
        }
      )
    }

    /** $autocloseable
      * $nonstrict
      * $seenow
      * $seedelay
      * $seereleasable
      */
    def autoCloseable[A <: AutoCloseable](future: Task[A]): Raii[A] = {
      val Task(TryT(continuation)) = future
      fromContinuation(
        continuation.map {
          case failure @ Failure(e) =>
            Resource[UnitContinuation, Try[A]](
              value = failure,
              release = UnitContinuation.pure(())
            )
          case success @ Success(closeable) =>
            Resource[UnitContinuation, Try[A]](
              value = success,
              release = {
                Continuation.delay(closeable.close())
              }
            )
        }
      )
    }

    /** $releasable
      * $nonstrict
      * $seenow
      * $seedelay
      * $seeautocloseable
      */
    def monadicCloseable[A <: MonadicCloseable[UnitContinuation]](future: UnitContinuation[A],
                                                                  dummyImplicit: DummyImplicit =
                                                                    DummyImplicit.dummyImplicit): Raii[A] = {
      monadicCloseable(Task(TryT(future.map(Success(_)))))
    }

    /** $autocloseable
      * $nonstrict
      * $seenow
      * $seedelay
      * $seereleasable
      */
    def autoCloseable[A <: AutoCloseable](continuation: UnitContinuation[A],
                                          dummyImplicit: DummyImplicit = DummyImplicit.dummyImplicit): Raii[A] = {
      autoCloseable(Task(TryT(continuation.map(Success(_)))))
    }

    /** $releasable
      * $nonstrict
      * $seenow
      * $seedelay
      * $seeautocloseable
      */
    def monadicCloseable[A <: MonadicCloseable[UnitContinuation]](value: => A): Raii[A] = {
      monadicCloseable(Task.delay(value))
    }

    /** $autocloseable
      * $nonstrict
      * $seenow
      * $seedelay
      */
    def autoCloseable[A <: AutoCloseable](value: => A): Raii[A] = {
      autoCloseable(Task.delay(value))
    }

    /** $delay
      * $nonstrict
      * $garbageCollected
      * $seenow
      * $seeautocloseable
      * $seedelay
      */
    def garbageCollected[A](future: Task[A]): Raii[A] = {
      val Task(TryT(continuation)) = future
      fromContinuation(
        continuation.map { either =>
          Resource.pure[UnitContinuation, Try[A]](either)
        }
      )
    }

    /** $delay
      * $nonstrict
      * $garbageCollected
      * $seenow
      * $seeautocloseable
      * $seedelay
      */
    def garbageCollected[A](continuation: UnitContinuation[A],
                            dummyImplicit: DummyImplicit = DummyImplicit.dummyImplicit): Raii[A] = {
      garbageCollected(Task(TryT(continuation.map(Success(_)))))
    }

    /** $delay
      * $nonstrict
      * $garbageCollected
      * $seenow
      * $seeautocloseable
      */
    def delay[A](value: => A): Raii[A] = {
      Raii(Parallel(TryT(ResourceT.delay(Try(value)))))
    }

    /** Returns a nested scope of `raiiA`.
      *
      * All resources created during building `A` will be released after `A` is built.
      *
      * @note `A` must be a garbage collected type, i.e. not a [[java.lang.AutoCloseable]] or a [[resourcet.MonadicCloseable]]
      * @note This method has the same behavior as `Raii.garbageCollected(raiiA.run)`.
      * @see [[garbageCollected]] for creating a garbage collected `Raii`
      * @see [[AsynchronousRaiiOps.run]] for running a `Raii` as a [[task.Task Task]].
      */
    def nested[A](raiiA: Raii[A]): Raii[A] = {
      val Raii(Parallel(TryT(resourceT))) = raiiA
      Raii(Parallel(TryT(ResourceT.nested(resourceT))))
    }

    /** $pure
      * $garbageCollected
      * $seedelay
      * $seeautocloseable
      */
    def pure[A](value: A): Raii[A] = {
      garbageCollected(Task.pure(value))
    }

    def async[A](start: (Resource[UnitContinuation, Try[A]] => Unit) => Unit): Raii[A] = {
      Raii(Parallel(TryT(ResourceT(UnitContinuation.async(start)))))
    }

    def safeAsync[A](start: (Resource[UnitContinuation, Try[A]] => TailRec[Unit]) => TailRec[Unit]): Raii[A] = {
      Raii(Parallel(TryT(ResourceT(UnitContinuation.safeAsync(start)))))
    }

    def suspend[A](doValue: => Raii[A]): Raii[A] = {
      Raii.safeAsync(doValue.safeOnComplete(_))
    }

    /** Returns a `Raii` that runs in `executorContext`.
      *
      * @note This method is usually used for changing the current thread.
      *
      *       {{{
      *       import java.util.concurrent._
      *       import scala.concurrent._
      *       import cats.syntax.all._
      *       import com.thoughtworks.zerocost.task._
      *       import com.thoughtworks.zerocost.raii._
      *
      *       implicit def executorContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())
      *
      *       val mainThread = Thread.currentThread
      *
      *       val assertionRaii = for {
      *         _ <- Raii.delay(())
      *         threadBeforeJump = Thread.currentThread
      *         _ = threadBeforeJump should be(mainThread)
      *         _ <- Raii.execute(())
      *         threadAfterJump = Thread.currentThread
      *       } yield {
      *         threadAfterJump shouldNot be(mainThread)
      *       }
      *       assertionRaii.run.toFuture
      *       }}}
      *
      * $delay
      * $nonstrict
      * $seenow
      * $seeautocloseable
      */
    def execute[A](value: => A)(implicit executorContext: ExecutionContext): Raii[A] = {
      garbageCollected(Task.execute(value))
    }

  }

  implicit final class RaiiOps[A](raii: Raii[A]) {

    def onComplete(continue: Resource[UnitContinuation, Try[A]] => Unit) = {
      val Raii(Parallel(TryT(ResourceT(continuation)))) = raii
      continuation.onComplete(continue)
    }

    def safeOnComplete(continue: Resource[UnitContinuation, Try[A]] => TailRec[Unit]) = {
      val Raii(Parallel(TryT(ResourceT(continuation)))) = raii
      continuation.safeOnComplete(continue)
    }

    /**
      * Returns a `Task` of `A`, which will open `A` and release all resources during opening `A`.
      *
      * @note `A` itself must be [[Raii.garbageCollected garbageCollected]](i.e. does not have clean up operation),
      *       though `A` may use some non-garbage-collected resources during opening `A`.
      */
    def run: Task[A] = {
      Task(TryT(ResourceT(toContinuation(raii)).run))
    }

    /** Returns a `Raii[B]` based on a `Raii[A]` and a function that creates a `Raii[B]`,
      * for those `B` do not reference to `A` or `A` is a garbage collected object.
      *
      * @note `intransitiveFlatMap` is similar to `flatMap` in [[raiiInstances]],
      *       except `intransitiveFlatMap` will release `A` right after `B` is created.
      *
      *       Raiin't use this method if you need to retain `A` until `B` is released.
      */
    def intransitiveFlatMap[B](f: A => Raii[B]): Raii[B] = {
      val resourceA = ResourceT(toContinuation(raii))
      val resourceB = resourceA.intransitiveFlatMap[Try[B]] {
        case Failure(e) =>
          ResourceT(Continuation.pure(Resource.pure(Failure(e))))
        case Success(value) =>
          ResourceT(toContinuation(f(value)))
      }
      val ResourceT(future) = resourceB
      fromContinuation(future)
    }

    /** Returns a `Raii[B]` based on a `Raii[A]` and a function that creates `B`,
      * for those `B` do not reference to `A` or `A` is a garbage collected object.
      *
      * @note `intransitiveMap` is similar to `map` in [[raiiInstances]],
      *       except `intransitiveMap` will release `A` right after `B` is created.
      *
      *       Don't use this method if you need to retain `A` until `B` is released.
      */
    def intransitiveMap[B](f: A => B): Raii[B] = {
      val resourceA = ResourceT(toContinuation(raii))
      val resourceB = resourceA.intransitiveMap(_.map(f))
      val ResourceT(future) = resourceB
      fromContinuation(future)
    }
    // TODO: reference counting
//    /** Converts `raii` to a reference counted wrapper.
//      *
//      * When the wrapper `Raii` is used by multiple larger `Raii` at the same time,
//      * only one `A` instance is created.
//      * The underlying `A` will be [[covariant.Resource.release release]]d only once,
//      * when all users [[covariant.Resource.release release]] the wrapper `Raii`.
//      */
//    def shared: Raii[A] = {
//      val sharedTask: RaiiContinuation[Try[A]] = TryT.unwrap(opacityTypes.unwrapRaii(raii)).shared
//      opacityTypes.wrapToRaii(TryT(sharedTask))
//    }
  }

  private def fromContinuation[A](future: UnitContinuation[Resource[UnitContinuation, Try[A]]]): Raii[A] = {
    Raii(Parallel(TryT[RaiiContinuation, A](ResourceT(future))))
  }

  private def toContinuation[A](doValue: Raii[A]): UnitContinuation[Resource[UnitContinuation, Try[A]]] = {
    val Raii(Parallel(TryT(ResourceT(future)))) = doValue
    future
  }
}
