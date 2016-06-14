import greg.{AssistantManager, _}

object OrderRunner extends App {
  val cashier = new Cashier(new OrderPrinter)
  val assi = new AssistantManager(cashier)
  val waiter = new Waiter(new RoundRobin(List(new Cook(assi, "Anke"), new Cook(assi, "Carsten"))))
  val orderIds = (1 to 3).map(i => waiter.placeOrder(42, List(LineItem("Steak", 1))))

  orderIds.foreach(cashier.paid)
}
