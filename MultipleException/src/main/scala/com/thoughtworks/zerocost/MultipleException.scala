package com.thoughtworks.zerocost

import java.io.{PrintStream, PrintWriter}

import cats.Semigroup

object MultipleException {
  implicit object throwableSemigroup extends Semigroup[Throwable] {
    override def combine(f1: Throwable, f2: Throwable): Throwable =
      f1 match {
        case MultipleException(exceptionSet1) =>
          f2 match {
            case MultipleException(exceptionSet2) => MultipleException(exceptionSet1 ++ exceptionSet2)
            case _: Throwable                     => MultipleException(exceptionSet1 + f2)
          }
        case _: Throwable =>
          f2 match {
            case MultipleException(exceptionSet2) => MultipleException(exceptionSet2 + f1)
            case _: Throwable                     => MultipleException(Set(f1, f2))
          }
      }
  }
}

final case class MultipleException(throwableSet: Set[Throwable]) extends Exception("Multiple exceptions found") {
  override def toString: String = throwableSet.mkString("\n")

  override def printStackTrace(): Unit = {
    for (throwable <- throwableSet) {
      throwable.printStackTrace()
    }
  }

  override def printStackTrace(s: PrintStream): Unit = {
    for (throwable <- throwableSet) {
      throwable.printStackTrace(s)
    }
  }

  override def printStackTrace(s: PrintWriter): Unit = {
    for (throwable <- throwableSet) {
      throwable.printStackTrace(s)
    }
  }

  override def getStackTrace: Array[StackTraceElement] = synchronized {
    super.getStackTrace match {
      case null =>
        setStackTrace(throwableSet.flatMap(_.getStackTrace)(collection.breakOut))
        super.getStackTrace
      case stackTrace =>
        stackTrace
    }
  }

  override def fillInStackTrace(): this.type = {
    this
  }

}
