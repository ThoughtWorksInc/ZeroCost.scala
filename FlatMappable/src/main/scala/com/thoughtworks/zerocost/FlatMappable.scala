package com.thoughtworks.zerocost

import cats.Monad

import scala.annotation.tailrec

trait FlatMappable[A] {
  def flatMap[B](f: A => FlatMappable[B]): FlatMappable[B]
}

object FlatMappable {

  trait TailCall[A] extends FlatMappable[A] {

    def step(): FlatMappable[A]

    @tailrec
    private def run: FlatMappable[A] = {
      step() match {
        case delay: TailCall[A] =>
          delay.run
        case x =>
          x
      }
    }

    override def flatMap[C](f: A => FlatMappable[C]): FlatMappable[C] = {
      run.flatMap(f)
    }
  }

  implicit object flatMappableInstances extends Monad[FlatMappable] {
    override def flatMap[A, B](fa: FlatMappable[A])(f: (A) => FlatMappable[B]): FlatMappable[B] = fa.flatMap(f)

    override def tailRecM[A, B](a: A)(f: (A) => FlatMappable[Either[A, B]]): FlatMappable[B] = {
      f(a).flatMap {
        case Left(continue) =>
          (() => tailRecM(continue)(f)): TailCall[B]
        case Right(break) =>
          pure(break)
      }

    }

    override def pure[A](a: A): FlatMappable[A] = new FlatMappable[A] {
      override def flatMap[B](f: (A) => FlatMappable[B]): FlatMappable[B] = f(a)
    }
  }

}
