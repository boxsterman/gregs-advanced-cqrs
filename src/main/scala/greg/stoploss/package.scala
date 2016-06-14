package greg


package object stoploss {

  trait MessageX

  case class PositionAcquired(price: Int) extends MessageX
  case class PriceUpdated(price: Int) extends MessageX

  case class TargetHit(price: Int) extends MessageX
  case class StopLossTargetPriceUpdated(price: Int) extends MessageX

  case class SendToMeIn(inSeconds: Int, message: MessageX) extends MessageX

  case class RemoveFrom11SecondsWindow(price: Int) extends MessageX
  case class RemoveFrom15SecondsWindow(price: Int) extends MessageX

}
