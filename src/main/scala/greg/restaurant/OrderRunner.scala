package greg.restaurant

import java.util.UUID

object OrderRunner extends App {

  val start = System.currentTimeMillis()

  // setup
  println("=> setup")
  //  val cashier = new Cashier(new OrderPrinter)
  val bus121 = new TopicBasedPubSub

  bus121.subscribe(new CountingHandler("Paid orders"))

  val cashier = new Cashier(bus121)
  bus121.subscribe(cashier)

  val assi = new ThreadedHandler(new AssistantManager(bus121), "assi")
  bus121.subscribe(assi)

  val cookAnke = new ThreadedHandler(new RandomFailureHandler(new TTLHandler(new Cook(bus121, 200, "Anke")), 0.05d), "Anke")
  val cookCarsten = new ThreadedHandler(new RandomFailureHandler(new TTLHandler(new Cook(bus121, 300, "Carsten")), 0.1d), "Carsten")
  val cookPaul = new ThreadedHandler(new RandomFailureHandler(new TTLHandler(new Cook(bus121, 500, "Paul")), 0.2d), "Paul")
  val mfDispatcher = new ThreadedHandler(new MFDispatcher(List(cookAnke, cookCarsten, cookPaul)), "MFDispatcher")
  bus121.subscribe(mfDispatcher)

  val waiter = new Waiter(bus121)

  val midgetHouse = new ThreadedHandler(new MidgetHouse(bus121, bus121))
  bus121.subscribe(midgetHouse)

  val ths = List(assi, cookAnke, cookPaul, cookCarsten, mfDispatcher, midgetHouse)

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
  var orderIds: List[UUID] = (1 to 10).map{ i =>
    val newOrderId = UUID.randomUUID()
    bus121.subscribe(newOrderId.toString, new OrderTracer)
    waiter.placeOrder(RestaurantOrder.newOrder(newOrderId).tableNumber(42).lineItems(List(LineItem("Steak", 1))).isDodgy(true))
  }.toList


  // Variant: only pay pending orders
  while(true) {
    cashier.ordersToPay.foreach(cashier.paid)
    Thread.sleep(500)
  }

  // done
  println(s"=> done in ${System.currentTimeMillis() - start} ms")
  System.exit(0)

}
