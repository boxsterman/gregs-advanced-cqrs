import java.util.UUID

import greg.{AssistantManager, _}

object OrderRunner extends App {

  val start = System.currentTimeMillis()

  // setup
  println("=> setup")
  //  val cashier = new Cashier(new OrderPrinter)
  val bus121 = new TopicBasedPubSub

  val cashier = new Cashier(bus121)
  bus121.subscribe(OrderPriced.toString, cashier)

  val assi = new ThreadedHandler(new AssistantManager(bus121), "assi")
  bus121.subscribe(FoodCooked.toString, assi)

  val cookAnke = new ThreadedHandler(new TTLHandler(new Cook(bus121, 200, "Anke")), "Anke")
  val cookCarsten = new ThreadedHandler(new TTLHandler(new Cook(bus121, 300, "Carsten")), "Carsten")
  val cookSven = new ThreadedHandler(new TTLHandler(new Cook(bus121, 500, "Paul")), "Paul")
  val mfDispatcher = new ThreadedHandler(new MFDispatcher(List(cookAnke, cookCarsten, cookSven)), "MFDispatcher")
  bus121.subscribe(OrderPlaced.toString, mfDispatcher)

  val waiter = new Waiter(bus121)

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
