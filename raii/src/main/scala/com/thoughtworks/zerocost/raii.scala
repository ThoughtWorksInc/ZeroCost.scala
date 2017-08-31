package com.thoughtworks.zerocost

import java.util.concurrent.atomic.AtomicReference

import com.thoughtworks.zerocost.tryt.covariant.{TryT, TryTLiftIO, TryTMonadError, TryTParallelApply}

import scala.concurrent.ExecutionContext
import scala.language.higherKinds
import scala.util.{Failure, Success, Try}
import TryT._
import cats.{Applicative, Monad, MonadError, Semigroup}
import com.thoughtworks.zerocost.task._
import com.thoughtworks.zerocost.continuation.{UnitContinuation, _}
import com.thoughtworks.zerocost.resourcet.covariant._
import com.thoughtworks.zerocost.parallel.covariant._
import com.thoughtworks.zerocost.resourcet.{
  CovariantResourceTLiftIO,
  CovariantResourceTMonad,
  CovariantResourceTParallelApply
}

import scala.util.control.NonFatal
import cats.syntax.all._

import scala.util.control.TailCalls.TailRec

/** The namespace that contains [[raii.Raii]].
  *
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object raii {

  private def fromContinuation[A](future: UnitContinuation[Resource[UnitContinuation, Try[A]]]): Raii[A] = {
    opacityTypes.fromTryT(TryT[RaiiContinuation, A](ResourceT(future)))
  }

  private def toContinuation[A](doValue: Raii[A]): UnitContinuation[Resource[UnitContinuation, Try[A]]] = {
    val ResourceT(TryT(future)) = opacityTypes.toTryT(doValue)
    //future
    ???
  }

  /** @template */
  private[raii] type RaiiContinuation[+A] = ResourceT[UnitContinuation, A]

  private[raii] trait OpacityTypes {
    type Raii[+A]
    type ParallelRaii[+A] = Parallel[Raii, A]

    private[raii] def fromTryT[A](run: TryT[RaiiContinuation, A]): Raii[A]

    private[raii] def toTryT[A](doValue: Raii[A]): TryT[RaiiContinuation, A]

    implicit private[raii] def raiiMonadErrorInstances: MonadError[Raii, Throwable] with LiftIO[Raii]

    implicit private[raii] def parallelRaiiMonadErrorInstances
      : MonadError[ParallelRaii, Throwable] with LiftIO[ParallelRaii]
  }

  private val UnitContinuationInstances = continuationInstances[Unit]

  private object RaiiContinuationInstances
      extends CovariantResourceTMonad[UnitContinuation]
      with CovariantResourceTLiftIO[UnitContinuation] {
    override implicit def F = UnitContinuationInstances
  }

  private object RaiiInstances extends TryTMonadError[RaiiContinuation] with TryTLiftIO[RaiiContinuation] {
    override implicit def F = RaiiContinuationInstances
  }

  private val ParallelContinuationInstances = parallelContinuationInstances

  private object ParallelRaiiContinuationInstances
      extends CovariantResourceTMonad[UnitContinuation]
      with CovariantResourceTLiftIO[UnitContinuation]
      with CovariantResourceTParallelApply[UnitContinuation] {
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
    override type Raii[+A] = TryT[RaiiContinuation, A]

    override private[raii] def fromTryT[A](run: TryT[RaiiContinuation, A]): TryT[RaiiContinuation, A] = run

    override private[raii] def toTryT[A](doa: TryT[RaiiContinuation, A]): TryT[RaiiContinuation, A] = doa

    implicit private[raii] def raiiMonadErrorInstances: MonadError[Raii, Throwable] with LiftIO[Raii] = RaiiInstances

    implicit private[raii] def parallelRaiiMonadErrorInstances
      : MonadError[Parallel[TryT[RaiiContinuation, +?], ?], Throwable] with LiftIO[
        Parallel[TryT[RaiiContinuation, +?], ?]] = ParallelRaiiInstances

  }

  /** An universal monadic data type that consists of many useful monad transformers.
    *
    * == Features of `Raii` ==
    *  - [[com.thoughtworks.tryt.covariant.TryT exception handling]]
    *  - [[com.thoughtworks.raii.covariant.ResourceT automatic resource management]]
    *  - [[raii.AsynchronousRaiiOps.shared reference counting]]
    *  - [[com.thoughtworks.continuation.UnitContinuation raii programming]]
    *  - [[ParallelRaii parallel computing]]
    *
    * @note This `Raii` type is an [[https://www.reddit.com/r/scala/comments/5qbdgq/value_types_without_anyval/dcxze9q/ opacity alias]] to `UnitContinuation[Resource[UnitContinuation, Try[A]]]`.
    * @see [[Raii$ Raii]] companion object for all type classes and helper functions for this `Raii` type.
    * @template
    */
  type Raii[+A] = opacityTypes.Raii[A]

  /** A [[Raii]] tagged as [[scalaz.Tags.Parallel Parallel]].
    *
    * @example `ParallelRaii` and [[Raii]] can be converted to each other via [[scalaz.Tags.Parallel]].
    *
    *          Given a [[Raii]],
    *
    *          {{{
    *          import com.thoughtworks.raii.raii.{Raii, ParallelRaii}
    *          import java.net._
    *          import java.io._
    *          val originalRaiiInput: Raii[InputStream] = Raii.autoCloseable(new Serializable with URL("http://thoughtworks.com/").openStream())
    *          }}}
    *
    *          when converting it to `ParallelRaii` and converting it back,
    *
    *          {{{
    *          import scalaz.Tags.Parallel
    *          val parallelRaiiInput: ParallelRaii[InputStream] = Parallel(originalRaiiInput)
    *          val Parallel(doInput) = parallelRaiiInput
    *          }}}
    *
    *          then the [[Raii]] should be still the original instance.
    *
    *          {{{
    *          doInput should be(originalRaiiInput)
    *          }}}
    *
    * @see [[doParallelApplicative]] for the [[scalaz.Applicative Applicative]] type class for parallel computing.
    *
    * @template
    */
  type ParallelRaii[A] = Parallel[Raii, A]

  /** Returns an [[scalaz.Applicative Applicative]] type class for parallel computing.
    *
    * @note This type class requires a [[scalaz.Semigroup Semigroup]] to combine multiple `Throwable`s into one,
    *       in the case of multiple tasks report errors in parallel.
    * @group Type classes
    */
  implicit def raiiMonadErrorInstances: MonadError[Raii, Throwable] =
    opacityTypes.raiiMonadErrorInstances

  /** @group Type classes */
  implicit def parallelRaiiMonadErrorInstances: MonadError[ParallelRaii, Throwable] =
    opacityTypes.parallelRaiiMonadErrorInstances

  /** The companion object of [[Raii]]
    * @define pure Converts a strict value to a `Raii` whose [[covariant.Resource.release release]] operation is no-op.
    *
    * @define seenow @see [[pure]] for strict garbage collected `Raii`
    *
    * @define delay Returns a non-strict `Raii` whose [[covariant.Resource.release release]] operation is no-op.
    *
    * @define seedelay @see [[delay]] for non-strict garbage collected `Raii`
    *
    * @define autocloseable Returns a non-strict `Raii` whose [[covariant.Resource.release release]] operation is [[java.lang.AutoCloseable.close]].
    *
    * @define releasable Returns a non-strict `Raii` whose [[covariant.Resource.release release]] operation is raii.
    *
    * @define seeautocloseable @see [[autoCloseable]] for auto-closeable `Raii`
    *
    * @define seereleasable @see [[monadicCloseable]] for creating a `Raii` whose [[covariant.Resource.release release]] operation is raii.
    *
    * @define nonstrict Since the `Raii` is non-strict,
    *                   `A` will be recreated each time it is sequenced into a larger `Raii`.
    *
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
      Raii(TryT(ResourceT(resourceContinuation)))
    }

    def apply[A](tryT: TryT[ResourceT[UnitContinuation, `+?`], A]): Raii[A] = {
      opacityTypes.fromTryT(tryT)
    }

    def unapply[A](doValue: Raii[A]): Some[TryT[ResourceT[UnitContinuation, `+?`], A]] = {
      Some(opacityTypes.toTryT(doValue))
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
            new Serializable with Resource[UnitContinuation, Try[A]] {
              override val value: Try[A] = Failure(e)
              override def release: UnitContinuation[Unit] = {
                UnitContinuation.pure(())
              }
            }
          case success @ Success(releasable) =>
            new Serializable with Resource[UnitContinuation, Try[A]] {
              override val value = Success(releasable)
              override def release: UnitContinuation[Unit] = releasable.monadicClose
            }
        }
      )
    }

    @deprecated(message = "Use [[autoCloseable]] instead.", since = "3.0.0")
    def scoped[A <: AutoCloseable](future: Task[A]): Raii[A] = {
      autoCloseable(future)
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
            new Serializable with Resource[UnitContinuation, Try[A]] {
              override val value: Try[A] = failure
              override val release: UnitContinuation[Unit] = {
                UnitContinuation.pure(())
              }
            }
          case success @ Success(closeable) =>
            new Serializable with Resource[UnitContinuation, Try[A]] {
              override val value: Try[A] = success
              override val release: UnitContinuation[Unit] = {
                Continuation.delay(closeable.close())
              }
            }
        }
      )
    }

    @deprecated(message = "Use [[autoCloseable]] instead.", since = "3.0.0")
    def scoped[A <: AutoCloseable](future: UnitContinuation[A],
                                   dummyImplicit: DummyImplicit = DummyImplicit.dummyImplicit): Raii[A] = {
      autoCloseable(future)
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
      Raii(TryT(ResourceT.delay(Try(value))))
    }

    /** Returns a nested scope of `doA`.
      *
      * All resources created during building `A` will be released after `A` is built.
      *
      * @note `A` must be a garbage collected type, i.e. not a [[java.lang.AutoCloseable]] or a [[com.thoughtworks.raii.covariant.MonadicCloseable]]
      * @note This method has the same behavior as `Raii.garbageCollected(doA.run)`.
      * @see [[garbageCollected]] for creating a garbage collected `Raii`
      * @see [[AsynchronousRaiiOps.run]] for running a `Raii` as a [[com.thoughtworks.future.Task ThoughtWorks Task]].
      */
    def nested[A](doA: Raii[A]): Raii[A] = {
      val Raii(TryT(resourceT)) = doA
      Raii(TryT(ResourceT.nested(resourceT)))
    }

    /** $now
      * $garbageCollected
      * $seedelay
      * $seeautocloseable
      */
    def pure[A](value: A): Raii[A] = {
      garbageCollected(Task.pure(value))
    }

    def async[A](start: (Resource[UnitContinuation, Try[A]] => Unit) => Unit): Raii[A] = {
      Raii(TryT(ResourceT(UnitContinuation.async(start))))
    }

    def safeAsync[A](start: (Resource[UnitContinuation, Try[A]] => TailRec[Unit]) => TailRec[Unit]): Raii[A] = {
      Raii(TryT(ResourceT(UnitContinuation.safeAsync(start))))
    }

    def suspend[A](doValue: => Raii[A]): Raii[A] = {
      Raii.safeAsync(doValue.safeOnComplete(_))
    }

    /** Returns a `Raii` that runs in `executorContext`.
      *
      * @note This method is usually been used for changing the current thread.
      *
      *       {{{
      *       import java.util.concurrent._
      *       import scala.concurrent._
      *       import scalaz.syntax.all._
      *       import com.thoughtworks.raii.raii._
      *
      *       implicit def executorContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())
      *
      *       val mainThread = Thread.currentThread
      *
      *       val doAssertion = for {
      *         _ <- Raii.delay(())
      *         threadBeforeJump = Thread.currentThread
      *         _ = threadBeforeJump should be(mainThread)
      *         _ <- Raii.execute(())
      *         threadAfterJump = Thread.currentThread
      *       } yield {
      *         threadAfterJump shouldNot be(mainThread)
      *       }
      *       doAssertion.run
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

  implicit final class AsynchronousRaiiOps[A](asynchronousRaii: Raii[A]) {

    def onComplete(continue: Resource[UnitContinuation, Try[A]] => Unit) = {
      val Raii(TryT(ResourceT(continuation))) = asynchronousRaii
      continuation.onComplete(continue)
    }

    def safeOnComplete(continue: Resource[UnitContinuation, Try[A]] => TailRec[Unit]) = {
      val Raii(TryT(ResourceT(continuation))) = asynchronousRaii
      continuation.safeOnComplete(continue)
    }

    /**
      * Returns a `Task` of `A`, which will open `A` and release all resources during opening `A`.
      *
      * @note `A` itself must be [[Raii.garbageCollected garbageCollected]](i.e. does not have clean up operation),
      *       though `A` may use some non-garbage-collected resources during opening `A`.
      */
    def run: Task[A] = {
      Task(TryT(ResourceT(toContinuation(asynchronousRaii)).run))
    }

    /** Returns a `Raii` of `B` based on a `Raii` of `A` and a function that creates a `Raii` of `B`,
      * for those `B` do not reference to `A` or `A` is a garbage collected object.
      *
      * @note `intransitiveFlatMap` is similar to `flatMap` in [[asynchronousRaiiMonadErrorInstances]],
      *       except `intransitiveFlatMap` will release `A` right after `B` is created.
      *
      *       Raiin't use this method if you need to retain `A` until `B` is released.
      */
    def intransitiveFlatMap[B](f: A => Raii[B]): Raii[B] = {
      val resourceA = ResourceT(toContinuation(asynchronousRaii))
      val resourceB = resourceA.intransitiveFlatMap[Try[B]] {
        case Failure(e) =>
          ResourceT(Continuation.pure(Resource.pure(Failure(e))))
        case Success(value) =>
          ResourceT(toContinuation(f(value)))
      }
      val ResourceT(future) = resourceB
      fromContinuation(future)
    }

    /** Returns a `Raii` of `B` based on a `Raii` of `A` and a function that creates `B`,
      * for those `B` do not reference to `A` or `A` is a garbage collected object.
      *
      * @note `intransitiveMap` is similar to `map` in [[asynchronousRaiiMonadErrorInstances]],
      *       except `intransitiveMap` will release `A` right after `B` is created.
      *
      *       Raiin't use this method if you need to retain `A` until `B` is released.
      */
    def intransitiveMap[B](f: A => B): Raii[B] = {
      val resourceA = ResourceT(toContinuation(asynchronousRaii))
      val resourceB = resourceA.intransitiveMap(_.map(f))
      val ResourceT(future) = resourceB
      fromContinuation(future)
    }

    // TODO: reference counting
//    /** Converts `asynchronousRaii` to a reference counted wrapper.
//      *
//      * When the wrapper `Raii` is used by multiple larger `Raii` at the same time,
//      * only one `A` instance is created.
//      * The underlying `A` will be [[covariant.Resource.release release]]d only once,
//      * when all users [[covariant.Resource.release release]] the wrapper `Raii`.
//      */
//    def shared: Raii[A] = {
//      val sharedTask: RaiiContinuation[Try[A]] = TryT.unwrap(opacityTypes.toTryT(asynchronousRaii)).shared
//      opacityTypes.fromTryT(TryT(sharedTask))
//    }
  }

}
