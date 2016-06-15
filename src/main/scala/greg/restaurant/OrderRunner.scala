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

  val cookAnke = new ThreadedHandler(new TTLHandler(new Cook(bus121, 200, "Anke")), "Anke")
  val cookCarsten = new ThreadedHandler(new TTLHandler(new Cook(bus121, 300, "Carsten")), "Carsten")
  val cookSven = new ThreadedHandler(new TTLHandler(new Cook(bus121, 500, "Paul")), "Paul")
  val mfDispatcher = new ThreadedHandler(new MFDispatcher(List(cookAnke, cookCarsten, cookSven)), "MFDispatcher")
  bus121.subscribe(mfDispatcher)

  val waiter = new Waiter(bus121)

  val midgetHouse = new ThreadedHandler(new MidgetHouse(bus121, bus121))
  bus121.subscribe(midgetHouse)

  val ths = List(assi, cookAnke, cookSven, cookCarsten, mfDispatcher, midgetHouse)

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
  var orderIds: List[UUID] = (1 to 2).map{ i =>
    val newOrderId = UUID.randomUUID()
    bus121.subscribe(newOrderId.toString, new OrderTracer)
    waiter.placeOrder(newOrderId, 42, List(LineItem("Steak", 1)))
  }.toList


  // Variant: only pay pending orders
  while(true) {
    cashier.ordersToPay.foreach(cashier.paid)
    Thread.sleep(500)
  }

  // Variant: all placed orders must be paid
//  while(orderIds.nonEmpty) {
//    orderIds.toList.foreach{ orderId =>
//      if(cashier.paid(orderId)) orderIds = orderIds diff List(orderId)
//    }
//    Thread.sleep(500)
//  }

  // done
  println(s"=> done in ${System.currentTimeMillis() - start} ms")
  System.exit(0)

//
//  def payFor(orderIds: List[UUID]): Unit = orderIds match {
//    case Nil =>
//    case x :: xs =>
//      if(cashier.paid(x))
//        payFor(xs)
//      else {
//        payFor(xs ::: List(x))
//        Thread.sleep(500)
//      }
//  }
//
//  def payFor(orderIds: List[UUID], unpaid: List[UUID]): List[UUID] = orderIds match {
//    case Nil => unpaid
//    case x :: xs =>
//      if(cashier.paid(x))
//        payFor(xs, unpaid)
//      else {
//        payFor(xs, )
//      }
//  }
}
