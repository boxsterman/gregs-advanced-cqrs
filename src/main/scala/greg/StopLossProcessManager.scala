package greg

class StopLossProcessManager(publisher: Message => Unit) {

  var stopLossPrice = -1
  var window11secs: List[Int] = Nil
  var window15secs: List[Int] = Nil
  // 'sold' is required since we can't just terminate
  var sold = false

  def handleMessage(m: Message) = m match  {

    case PositionAcquired(p) =>
      stopLossPrice = p
      publisher(StopLossTargetPriceUpdated(p))

    case PriceUpdated(p) =>
      publisher(SendToMeIn(11, RemoveFrom11SecondsWindow(p)))
      window11secs = p :: window11secs
      publisher(SendToMeIn(15, RemoveFrom15SecondsWindow(p)))
      window15secs = p :: window15secs

    case RemoveFrom11SecondsWindow(p) =>
      val min = window11secs.min
      window11secs = window11secs diff List(p)
      if(min > stopLossPrice + 1) {
        stopLossPrice = min
        publisher(StopLossTargetPriceUpdated(min))
      }

    case RemoveFrom15SecondsWindow(p) =>
      val max = window15secs.max
      window15secs = window15secs diff List(p)
      if(max < stopLossPrice && !sold) {
        sold = true
        publisher(TargetHit(max))
      }
  }
}