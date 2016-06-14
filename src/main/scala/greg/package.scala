
package object greg {

  trait Message

  case class PositionAcquired(price: Int) extends Message
  case class PriceUpdated(price: Int) extends Message

  case class TargetHit(price: Int) extends Message
  case class StopLossTargetPriceUpdated(price: Int) extends Message

  case class SendToMeIn(inSeconds: Int, message: Message) extends Message

  case class RemoveFrom11SecondsWindow(price: Int) extends Message
  case class RemoveFrom15SecondsWindow(price: Int) extends Message

}
