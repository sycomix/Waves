package com.wavesplatform.matcher.fixtures

import akka.actor.Actor
import akka.persistence.PersistentActor
import com.wavesplatform.matcher.fixtures.RestartableActor.{RestartActor, RestartActorException}

trait RestartableActor {
  this: Actor =>

  // TODO
  /*abstract override */
  def receiveCommand: PartialFunction[Any, Unit] = { case _ => } /*super.receiveCommand orElse {
    case RestartActor => throw RestartActorException
  }*/
}

object RestartableActor {
  case object RestartActor

  private object RestartActorException extends Exception("Planned restart")
}
