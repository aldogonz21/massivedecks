package controllers.massivedecks.notifications

import scala.concurrent.ExecutionContext

import models.massivedecks.Game
import models.massivedecks.Game.Formatters._
import models.massivedecks.Player
import models.massivedecks.Player.Formatters._
import models.massivedecks.Lobby
import models.massivedecks.Lobby.Formatters._
import play.api.libs.iteratee.{Concurrent, Enumerator, Iteratee}
import play.api.libs.json.{JsValue, Json}

/**
  * Manages open sockets.
  */
class Notifiers (implicit context: ExecutionContext) {

  val (broadcastEnumerator, broadcastChannel) = Concurrent.broadcast[String]
  var identified: Map[Player.Id, Notifier] = Map()

  /**
    * Sets up notifications for a new websocket.
    *
    * @param onIdentify When we get an identification message from the client, this will be run. This should validate
    *                   the secret and throw if it isn't correct.
    * @param onClose When a client that has previously identified disconnects, this will be run.
    * @return The websocket iteratee and enumerator.
    */
  def openedSocket(onIdentify: Player.Secret => Lobby.LobbyAndHand, onClose: (Player.Id => Unit)): (Iteratee[String, Unit], Enumerator[String]) = {
    val notifier = new Notifier()
    val (unicastIteratee, unicastEnumerator) = notifier.openedSocket(
      () => notifier.notify("identify"),
      (secret) => {
        val lobbyAndHand = onIdentify(secret)
        identified += (secret.id -> notifier)
        sync(secret.id, lobbyAndHand)
      },
      () => {
        identified.find(item => notifier == item._2).foreach { item =>
          val (id, _) = item
          identified -= id
          onClose(id)
        }
      }
    )
    (unicastIteratee, unicastEnumerator.interleave(broadcastEnumerator))
  }


  def sync(playerId: Player.Id, lobbyAndHand: Lobby.LobbyAndHand): Unit =
    notify(playerId, Json.obj(
      "event" -> "Sync",
      "lobbyAndHand" -> Json.toJson(lobbyAndHand)
    ))


  def playerJoin(player: Player): Unit =
    notifyAll(Json.obj(
      "event" -> "PlayerJoin",
      "player" -> Json.toJson(player)
    ))

  def playerStatus(playerId: Player.Id, status: Player.Status): Unit =
    notifyAll(Json.obj(
      "event" -> "PlayerStatus",
      "player" -> Json.toJson(playerId),
      "status" -> Json.toJson(status)
    ))

  def playerLeft(playerId: Player.Id): Unit =
    notifyAll(Json.obj(
      "event" -> "PlayerLeft",
      "player" -> Json.toJson(playerId)
    ))

  def playerDisconnect(playerId: Player.Id): Unit =
    notifyAll(Json.obj(
      "event" -> "PlayerDisconnect",
      "player" -> Json.toJson(playerId)
    ))

  def playerReconnect(playerId: Player.Id): Unit =
    notifyAll(Json.obj(
      "event" -> "PlayerReconnect",
      "player" -> Json.toJson(playerId)
    ))

  def playerScoreChange(playerId: Player.Id, score: Int): Unit =
    notifyAll(Json.obj(
      "event" -> "PlayerScoreChange",
      "player" -> Json.toJson(playerId),
      "score" -> Json.toJson(score)
    ))


  def handChange(playerId: Player.Id, hand: Game.Hand): Unit =
    notify(playerId, Json.obj(
      "event" -> "HandChange",
      "hand" -> Json.toJson(hand)
    ))


  def roundStart(czar: Player.Id, call: Game.Call): Unit =
    notifyAll(Json.obj(
      "event" -> "RoundStart",
      "czar" -> Json.toJson(czar),
      "call" -> Json.toJson(call)
    ))

  def roundPlayed(playedCards: Int): Unit =
    notifyAll(Json.obj(
      "event" -> "RoundPlayed",
      "playedCards" -> Json.toJson(playedCards)
    ))

  def roundJudging(playedCards: List[List[Game.Response]]): Unit =
    notifyAll(Json.obj(
      "event" -> "RoundJudging",
      "playedCards" -> Json.toJson(playedCards)
    ))

  def roundEnd(finishedRound: Game.FinishedRound): Unit =
    notifyAll(Json.obj(
      "event" -> "RoundEnd",
      "finishedRound" -> Json.toJson(finishedRound)
    ))


  def gameStart(): Unit =
    notifyAll(Json.obj(
      "event" -> "GameStart"
    ))

  def gameEnd(): Unit =
    notifyAll(Json.obj(
      "event" -> "GameEnd"
    ))


  def configChange(config: Game.Config): Unit =
    notifyAll(Json.obj(
      "event" -> "ConfigChange",
      "config" -> Json.toJson(config)
    ))


  private def notify(player: Player.Id, event: JsValue) =
    identified.get(player).foreach { notifier => notifier.notify(event.toString) }

  private def notifyAll(event: JsValue) =
    broadcastChannel.push(event.toString)

}