import java.util.UUID

import greg.{AssistantManager, _}

object OrderRunner extends App {

  val start = System.currentTimeMillis()

  // setup
  println("=> setup")
//  val cashier = new Cashier(new OrderPrinter)
  val cashier = new Cashier(new NullHandler)
  val assi = new ThreadedHandler(new AssistantManager(cashier), "assi")
  val cookAnke = new ThreadedHandler(new Cook(assi, 500, "Anke"), "Anke")
  val cookCarsten = new ThreadedHandler(new Cook(assi, 800, "Carsten"), "Carsten")
  val cookSven = new ThreadedHandler(new Cook(assi, 1200, "Sven"), "Sven")
  val mfDispatcher = new ThreadedHandler(new MFDispatcher(List(cookAnke, cookCarsten, cookSven)), "MFDispatcher")
  val waiter = new Waiter(mfDispatcher)

  val ths = List(assi, cookAnke, cookSven, cookCarsten, mfDispatcher)
  // start
  println("=> start")
  ths.foreach(_.start)

  new Thread(new Runnable {
    def run(): Unit = {
      println("Monitor started")
      while(true) {
        ths.foreach(th => print(s"${th.name}: ${th.count} "))
        println("")
        Thread.sleep(200)
      }
    }
  }).start()

  // run it
  println("=> run")
  var orderIds: List[UUID] = (1 to 100).map(i => waiter.placeOrder(42, List(LineItem("Steak", 1)))).toList

  while(orderIds.nonEmpty) {
    orderIds.toList.foreach{ orderId =>
      if(cashier.paid(orderId)) orderIds = orderIds diff List(orderId)
    }
    Thread.sleep(500)
  }

  // done
  println(s"=> done in ${System.currentTimeMillis() - start} ms")
  System.exit(0)
}
