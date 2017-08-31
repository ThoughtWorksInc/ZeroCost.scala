package com.thoughtworks.zerocost

import cats.Monad

import scala.annotation.tailrec

trait FlatMappable[A] {
  import com.thoughtworks.zerocost.FlatMappable._

  def flatMap[B](f: A => FlatMappable[B]): FlatMappable[B]

}

object FlatMappable {

  trait TailCall[A] extends FlatMappable[A] {

    def step(): FlatMappable[A]

    @tailrec
    private def run: FlatMappable[A] = {
      step() match {
        case tailCall: TailCall[A] =>
          tailCall.run
        case notTailCall =>
          notTailCall
      }
    }

    override final def flatMap[B](f: A => FlatMappable[B]): FlatMappable[B] = {
      run.flatMap { b =>
        (() => f(b)): TailCall[B]
      }
    }
  }

  final case class Pure[A](a: A) extends FlatMappable[A] {
    override def flatMap[B](f: (A) => FlatMappable[B]): TailCall[B] = {
      (() => f(a)): TailCall[B]
    }
  }

  implicit object flatMappableInstances extends Monad[FlatMappable] {
    override def flatMap[A, B](fa: FlatMappable[A])(f: (A) => FlatMappable[B]): FlatMappable[B] = fa.flatMap(f)

    override def tailRecM[A, B](a: A)(f: (A) => FlatMappable[Either[A, B]]): FlatMappable[B] = {
      f(a).flatMap {
        case Left(continue) =>
          tailRecM(continue)(f)
        case Right(break) =>
          pure(break)
      }
    }

    override def pure[A](a: A): FlatMappable[A] = Pure(a)
  }

  trait Async[A] extends FlatMappable[A] { outer =>
    def onComplete(continueA: (A => Unit)): Unit
    override final def flatMap[B](fab: (A) => FlatMappable[B]): FlatMappable[B] = {
      new Async[B] {
        override def onComplete(continueB: (B) => Unit): Unit = {
          outer.onComplete { a =>
            fab(a) match {
              case async: Async[B] =>
                async.onComplete { b =>
                  continueB(b)
                }
              // TODO: Other FlatMappable
            }
          }
        }
      }
    }
  }

}
