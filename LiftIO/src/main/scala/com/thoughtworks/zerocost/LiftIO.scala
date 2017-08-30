package com.thoughtworks.zerocost
import simulacrum.typeclass

import scala.language.implicitConversions
import scala.language.higherKinds

/**
  * @author 杨博 (Yang Bo)
  */
@typeclass
trait LiftIO[F[_]] {

  import LiftIO._

  @inline
  def liftIO[A](io: IO[A]): F[A]

  @inline
  def delay[A](a: => A): F[A] = liftIO(a _)

}

object LiftIO {

  implicit object function0LiftIO extends LiftIO[Function0] {

    override def liftIO[A](io: IO[A]): Function0[A] = io

  }

  type IO[+A] = Function0[A]

}
