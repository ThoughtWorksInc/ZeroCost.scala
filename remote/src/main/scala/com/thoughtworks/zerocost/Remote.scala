package com.thoughtworks.zerocost

import java.io.{ObjectOutputStream, ByteArrayOutputStream, ObjectInputStream, ByteArrayInputStream}
import scala.util.{Failure, Success, Try, DynamicVariable}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.event.{Logging, LogSource}
import akka.pattern.ask
import akka.util.Timeout
import com.thoughtworks.zerocost.tryt._
import com.thoughtworks.zerocost.continuation._
import com.thoughtworks.zerocost.task._
import com.thoughtworks.zerocost.raii._
import com.thoughtworks.zerocost.resourcet._
import scala.concurrent.duration.{SECONDS, FiniteDuration}
import cats.syntax.all._

/** The only type of message a [[RemoteActor]] receives.
  * It really should contain [[Remote.RemoteContinuation[ActorRef]]], but we send raw buffer to apply a bit of hack to the default Java deserialization process.
  */
case class Dispatch(remoteContinuationBuffer: Array[Byte])

/**
  * The reply message a [[RemoteActor]] guarantees to send.
  */
case class Receipt[A](receipt: Try[A])

class RemoteActor extends Actor {

  import Remote.{RemoteContinuation, actorSystemStore}

  override def receive: Receive = {
    case Dispatch(remoteContinuationBuffer) =>
      val remoteContinuation: RemoteContinuation[ActorRef] = actorSystemStore.withValue(context.system) {
        new ObjectInputStream(new ByteArrayInputStream(remoteContinuationBuffer))
          .readObject()
          .asInstanceOf[RemoteContinuation[ActorRef]]
      }
      val value: Try[ActorRef] = Success(self)
      val actorSystem = context.system.asInstanceOf[Remote].actorSystem
      val release: UnitContinuation[Unit] = UnitContinuation.delay {
        actorSystem.stop(self)
      }
      val remoteContinuationParameter: Resource[UnitContinuation, Try[ActorRef]] = Resource(value, release)
      remoteContinuation(remoteContinuationParameter)
      sender ! Receipt(value)
  }
}

/** The class that implements automatic remote execution.
  *
  * @author 邵成 (Shao Cheng) &lt;astrohavoc@gmail.com&gt;
  * @example Given a lazy [[ActorSystem]] and an implicit [[Timeout]] in scope, one can construct a [[Raii[Remote]]]
  *          {{{
  *          import com.thoughtworks.zerocost.raii._
  *          import com.thoughtworks.zerocost.task._
  *          import akka.actor.{Actor, ActorRef, ActorSystem, Props}
  *          import akka.util.Timeout
  *          import scala.concurrent.duration.{SECONDS, FiniteDuration}
  *          import cats.syntax.all._
  *          import com.thoughtworks.each.Monadic._
  *
  *          implicit val timeout: Timeout = FiniteDuration(10, SECONDS)
  *          val makeRemote: Raii[Remote] = Remote(ActorSystem("actorSystem"))
  *          }}}
  *
  *          The [[Remote]] type exposes a [[jump]] interface, which can be used as follows:
  *          (the first snippet works with an upcoming major version of each)
  *
  *         `<pre>
  *          val m: Raii[Int] = monadic[Raii] {
  *            val remote = makeRemote.each
  *            val x = Raii.pure(6).each
  *            remote.jump.each
  *            val y = Raii.pure(7).each
  *            x * y
  *          }
  *          </pre>`
  *
  *          {{{
  *          val m: Raii[Int] = makeRemote.flatMap { remote => {
  *            Raii.pure(6).flatMap { x =>
  *              remote.jump.flatMap { _ =>
  *                Raii.pure(7).flatMap { y =>
  *                 Raii.pure(x * y)
  *                }
  *              }
  *            }
  *          }}
  *          m.run.blockingAwait should be(42)
  *          }}}
  */

class Remote(val actorSystem: ActorSystem)(implicit val timeout: Timeout) extends Serializable {

  import Remote._
  import actorSystem.dispatcher

  val log = {
    implicit val logSource: LogSource[Remote] = new Serializable with LogSource[Remote] {
      override def genString(t: Remote): String = toString
    }
    Logging(actorSystem, this)
  }

  log.info(s"remote context $this constructed")

  def jump: Raii[ActorRef] = Raii.async { (remoteContinuation: RemoteContinuation[ActorRef]) =>
  {
    log.info(s"jump of $this is invoked")

    val newActor = actorSystem.actorOf(Props(new RemoteActor))
    val remoteContinuationBuffer = {
      val byteArrayOutputStream = new ByteArrayOutputStream()
      new ObjectOutputStream(byteArrayOutputStream).writeObject(remoteContinuation)
      byteArrayOutputStream.toByteArray
    }

    (newActor ? Dispatch(remoteContinuationBuffer)).onComplete {
      case Success(Receipt(receipt)) =>
        receipt match {
          case Success(returnedActor) => log.info(s"remote execution returned from $returnedActor")
          case Failure(err)           => log.error(s"remote execution failed with $err")
        }
      case Failure(err) => log.error(s"akka messaging failed with $err")
    }

  }
  }

  def writeReplace: Any = {
    log.info(s"writeReplace of $this is invoked")
    RemoteProxy
  }
}

case object Remote {
  type RemoteContinuation[A] = (Resource[UnitContinuation, Try[A]]) => Unit

  val actorSystemStore: DynamicVariable[ActorSystem] = new DynamicVariable[ActorSystem](null)

  object RemoteProxy extends Serializable {
    def readResolve: Any = {
      val actorSystem = actorSystemStore.value
      implicit val timeout: Timeout = FiniteDuration(10, SECONDS)
      val remote = new Remote(actorSystem)
      val log = {
        implicit val logSource: LogSource[Remote] = new Serializable with LogSource[Remote] {
          override def genString(t: Remote): String = toString
        }
        Logging(remote.actorSystem, remote)
      }
      log.info(s"readResolve of $remote is invoked")
      remote
    }
  }

  def apply(makeActorSystem: => ActorSystem)(implicit timeout: Timeout): Raii[Remote] = {
    Raii.resource {
      val actorSystem = makeActorSystem
      Resource(
        new Remote(actorSystem),
        UnitContinuation.delay {
          import actorSystem.dispatcher
          val Task(TryT(tryFinalizer)) = actorSystem.terminate.toTask
          val log = {
            implicit val logSource: LogSource[Remote.this.type] = new Serializable with LogSource[Remote.this.type] {
              override def genString(t: Remote.this.type): String = toString
            }
            Logging(actorSystem, this)
          }
          tryFinalizer.map {
            case Success(_)   => log.info(s"actorSystem $actorSystem terminated")
            case Failure(err) => log.error(s"termination of actorSystem $actorSystem failed with $err")
          }
        }.flatten
      )
    }
  }
}
