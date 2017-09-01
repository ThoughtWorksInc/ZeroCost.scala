package com.thoughtworks.zerocost

import akka.actor.Status.Success
import akka.actor.{Actor, ActorRef, ActorSystem, OneForOneStrategy, Props, SupervisorStrategy}
import com.thoughtworks.zerocost.continuation.UnitContinuation
import com.thoughtworks.zerocost.raii.{Raii, _}
import com.thoughtworks.zerocost.resourcet.Resource

import scala.annotation.tailrec
import scala.util.Try
import scala.util.control.NonFatal
import scala.util.control.TailCalls.TailRec

///**
//  * @author 杨博 (Yang Bo)
//  */
//class Remote extends Actor {
//  override def receive = ???
//}

trait Remote {

  def execute[A](f: Remote => Raii[A]): Raii[A]

}

// TODO:
// 1. 把现有代码搬到object Remote里面，免得闭包不小心引用了Actor
// 2. 先不用watch，假装不会出错，实现基于Raii的远程调用（不要试图封装成Future或者UnitContinuation，因为即使封装了，用map/flatMap也仍然会导致序列化过大的问题，必须避免封装成异步对象）
// 3. 加上watch

object Remote {

  private trait OpacityTypes {
    type RemoteObjectToken[+A] <: Int
    def wrapToRemoteObjectToken[A](token: Int): RemoteObjectToken[A]
  }

  private val opacityTypes = new OpacityTypes {
    override type RemoteObjectToken[+A] = Int

    override def wrapToRemoteObjectToken[A](token: Int) = token
  }
  import opacityTypes._

  final case class Complete[A](a: A)
  final case class Rpc()

  final class RemoteActor[A](f: Remote => Raii[A]) extends Actor with Remote {

    f(this).onComplete { resource =>
      post(_.completeHandler(resource))
    }

    private val children = scala.collection.mutable.HashMap.empty[ActorRef, Resource[UnitContinuation, Try[_]] => Unit]

    private var tokenSeed = 0

    @tailrec
    private def findAvailableToken(rawToken: Int): Int = {
      if (remoteObjects.contains(rawToken)) {
        findAvailableToken(rawToken + 1)
      } else {
        rawToken
      }
    }

    private def nextToken(): Int = {
      val rawToken = findAvailableToken(tokenSeed)
      tokenSeed = rawToken + 1
      rawToken
    }

    private val remoteObjects = scala.collection.mutable.HashMap.empty[Int, Any]

    private def store[A](x: A): RemoteObjectToken[A] = {
      val rawToken = nextToken()
      remoteObjects(rawToken) = x
      wrapToRemoteObjectToken(rawToken)
    }

    private def get(token: RemoteObjectToken[A]): A = {
      remoteObjects(token).asInstanceOf[A]
    }

    private def release(releaseToken: RemoteObjectToken[UnitContinuation[Unit]]): Unit = {
      get(releaseToken)
      ???
    }

    private def remoteSuccess[B](b: B, releaseToken: RemoteObjectToken[UnitContinuation[Unit]]): Unit = {
      val ownerRef = sender()
      Resource(b, UnitContinuation.delay {
        ownerRef ! { owner: RemoteActor[B] =>
          owner.release(releaseToken)
        }
      })

      ???
    }

    private def completeHandler(resource: Resource[UnitContinuation, Try[A]]): Unit = {
      val Resource(tryA, releaseA) = resource

      tryA match {
        case scala.util.Success(a) =>
          val releaseToken = store(releaseA)
          context.parent ! { parent: RemoteActor[_] =>
            parent.remoteSuccess(a, releaseToken)
          }
        case scala.util.Failure(e) =>
          ???
      }

    }

    override def receive: Receive = {
      case message =>
        message.asInstanceOf[this.type => Unit](this)
    }

    private def start[B](remoteCode: Remote => Raii[B], continue: Resource[UnitContinuation, Try[B]] => Unit): Unit = {
      val actorRef = context.actorOf(Props(new RemoteActor(remoteCode)))
      children(actorRef) = continue.asInstanceOf[Resource[UnitContinuation, Try[_]] => Unit]
    }

    override def execute[B](remoteCode: Remote => Raii[B]): Raii[B] = {
      Raii.async { continue =>
        post(_.start(remoteCode, continue))
      }
    }

    private def post(f: this.type => Unit): Unit = {
      self ! f
    }

    private def postTo[A](actorRef: ActorRef)(f: RemoteActor[A] => Unit): Unit = {
      actorRef ! f
    }

    override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
      case NonFatal(e) =>
        sender()
        ???
    }
  }

//  trait Akka extends Remote {
//    def actorSystem: ActorSystem
//
//    override def execute[A](f: Remote => Raii[A]): Raii[A] = {
//
//      Raii.async { continue =>
//        val actorRef = actorSystem.actorOf(Props(new RemoteActor(f)))
//
//        ???
//      }
//
//    }
//  }

//
//  def root(actorSystem: ActorSystem): Remote = {
//    new Remote {
//      override def execute[A](f: (Remote) => Raii[A]): Raii[A] = {
//
//        actorSystem.actorOf()
//        ???
//      }
//    }
//
//  }

}
