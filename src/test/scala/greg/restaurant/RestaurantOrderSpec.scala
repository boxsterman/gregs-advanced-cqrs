package greg.restaurant

import java.util.UUID

import org.scalatest.{Matchers, WordSpec}
import play.api.libs.json.Json


class OrderAsserter[T <: Message](next: Handler[T])(assert: T => Unit) extends Handler[T] {

  def handle(t: T): Unit = {
    assert(t)
    next.handle(t)
  }
}

class RestaurantOrderSpec extends WordSpec with Matchers {


  val json1 =
    Json.prettyPrint(Json.parse("""
      | {
      |   "id": "6e9cf693-9e7f-490a-b362-a379fe7d5496",
      |   "tableNumber": 1,
      |   "ingredients": [],
      |   "lineItems": [
      |     {"name": "Steak", "qty": 1, "price": 1}
      |   ]
      | }
    """.stripMargin))



  "A Scheduler" should {
    "send a delayed message" in {
      var events: List[Message] = Nil
      val bus = new CanPublish {
        def publish[T <: Message](event: T)(implicit ct: ClassManifest[T]): Unit = events = event :: events
        def publish[T <: Message](name: String, event: T): Unit = events = event :: events
      }

      val s = new Scheduler(bus)
      s.start
      val toSend = OrderPaid(null, null, null)
      s.handle(DelayedPublish(toSend, 1, "", ""))
      Thread.sleep(300)
      events shouldBe empty

      Thread.sleep(900)
      events.size should equal(1)
      events.head should equal(toSend)
    }
  }

  "A PayAfterMidget" should {

    "respond properly" in {

      var events: List[Message] = Nil
      var midgetCompleted = false
      val bus = new CanPublish {
        def publish[T <: Message](event: T)(implicit ct: ClassManifest[T]): Unit = events = event :: events
        def publish[T <: Message](name: String, event: T): Unit = events = event :: events
      }

      val order = RestaurantOrder.newOrder(UUID.randomUUID())
      val orderPlaced = OrderPlaced(order, "17", "no")
      val m = new PayAfterMidget(bus, orderPlaced, x => midgetCompleted = true)

      m.start()

      midgetCompleted shouldBe false
      events.size should equal(1)
      events.head.corrId should equal("17")
      events.head.causeId should equal(orderPlaced.msgId)
      events.head match {
        case e: CookFood =>
        case e => fail(s"Unexpected type: $e")
      }

      events = Nil

      val foodCooked = FoodCooked(order, "17", "no")
      m.handle(foodCooked)

      midgetCompleted shouldBe false
      events.size should equal(1)
      events.head.corrId should equal("17")
      events.head.causeId should equal(foodCooked.msgId)
      events.head match {
        case e: PriceOrder =>
        case e => fail(s"Unexpected type: $e")
      }

      events = Nil

      val orderPriced = OrderPriced(order, "17", "no")
      m.handle(orderPriced)

      midgetCompleted shouldBe false
      events.size should equal(1)
      events.head.corrId should equal("17")
      events.head.causeId should equal(orderPriced.msgId)
      events.head match {
        case e: TakePayment =>
        case e => fail(s"Unexpected type: $e")
      }

      events = Nil

      val orderPaid = OrderPaid(order, "17", "no")
      m.handle(orderPaid)

      midgetCompleted shouldBe true
      events.size should equal(0)
    }

  }

  "A PayFirstMidget" should {

    "respond properly" in {

      var events: List[Message] = Nil
      var midgetCompleted = false

      val bus = new CanPublish {
        def publish[T <: Message](event: T)(implicit ct: ClassManifest[T]): Unit = events = event :: events
        def publish[T <: Message](name: String, event: T): Unit = events = event :: events
      }

      val order = RestaurantOrder.newOrder(UUID.randomUUID())
      val orderPlaced = OrderPlaced(order, "17", "no")
      val m = new PayFirstMidget(bus, orderPlaced, x => midgetCompleted = true)

      m.start()

      midgetCompleted shouldBe false
      events.size should equal(1)
      events.head.corrId should equal("17")
      events.head.causeId should equal(orderPlaced.msgId)
      events.head match {
        case e: PriceOrder=>
        case e => fail(s"Unexpected type: $e")
      }

      events = Nil

      val orderPriced = OrderPriced(order, "17", "no")
      m.handle(orderPriced)

      midgetCompleted shouldBe false
      events.size should equal(1)
      events.head.corrId should equal("17")
      events.head.causeId should equal(orderPriced.msgId)
      events.head match {
        case e: TakePayment =>
        case e => fail(s"Unexpected type: $e")
      }

      events = Nil

      val orderPaid = OrderPaid(order, "17", "no")
      m.handle(orderPaid)

      midgetCompleted shouldBe false
      events.size should equal(1)
      events.head.corrId should equal("17")
      events.head.causeId should equal(orderPaid.msgId)
      events.head match {
        case e: CookFood =>
        case e => fail(s"Unexpected type: $e")
      }

      events = Nil

      val foodCooked = FoodCooked(order, "17", "no")
      m.handle(foodCooked)

      midgetCompleted shouldBe true
      events.size should equal(0)

    }

  }

  "A MidgetHouse" should {
    "create PayFirstMidget for dodgy order" in {

      var events: List[Message] = Nil
      val bus = new CanPublish with CanSubscribe {

        def subscribe[T <: Message](handler: Handler[T])(implicit ct: ClassManifest[T]): Unit = ???

        def subscribe[T <: Message](name: String, handler: Handler[T]): Unit = ???

        def publish[T <: Message](event: T)(implicit ct: ClassManifest[T]): Unit = events = event :: events
        def publish[T <: Message](name: String, event: T): Unit = events = event :: events
      }

      val mh = new MidgetHouse(bus, bus)
      val order = RestaurantOrder.newOrder(UUID.randomUUID()).isDodgy(true)
      val orderPlaced = OrderPlaced(order, "17", "no")

      mh.midgetForOrder(bus,orderPlaced) match {
        case m: PayFirstMidget =>
        case m =>  fail(s"Unexpected type: $m")
      }

    }

    "create PayAfterMidget for non dodgy order" in {

      var events: List[Message] = Nil
      val bus = new CanPublish with CanSubscribe {

        def subscribe[T <: Message](handler: Handler[T])(implicit ct: ClassManifest[T]): Unit = ???

        def subscribe[T <: Message](name: String, handler: Handler[T]): Unit = ???

        def publish[T <: Message](event: T)(implicit ct: ClassManifest[T]): Unit = events = event :: events
        def publish[T <: Message](name: String, event: T): Unit = events = event :: events

      }

      val mh = new MidgetHouse(bus, bus)
      val order = RestaurantOrder.newOrder(UUID.randomUUID()).isDodgy(false)
      val orderPlaced = OrderPlaced(order, "17", "no")

      mh.midgetForOrder(bus,orderPlaced) match {
        case m: PayAfterMidget =>
        case m =>  fail(s"Unexpected type: $m")
      }

    }

  }

  "A Restaurant Order" should {

//    "have the waiter placing an order" in {
//      new Waiter(new OrderAsserter(new OrderPrinter)({ o =>
//        o.tableNumber should equal(42)
//        o.lineItems should have size(1)
//      })).placeOrder(42, List(LineItem("Steak", 1)))
//    }
//
//    "have the cook preparing a placed order" in {
//      new Waiter(new Cook(new OrderAsserter(new OrderPrinter)({ o =>
//        o.tableNumber should equal(42)
//        o.lineItems should have size(1)
//        o.ingredients should have size 4
//      }))).placeOrder(42, List(LineItem("Steak", 1)))
//    }
//
//    "have the assistant manager pricing the order" in {
//      new Waiter(new Cook(new AssistantManager(new OrderAsserter(new OrderPrinter)({ o =>
//        o.tableNumber should equal(42)
//        o.lineItems should have size(1)
//        o.ingredients should have size 4
//        o.total should equal(34.0)
//      })))).placeOrder(42, List(LineItem("Steak", 1)))
//    }
//
//    "have the cashier getting paid" in {
//      val cashier = new Cashier(new OrderAsserter(new OrderPrinter)({ o =>
//        o.tableNumber should equal(42)
//        o.lineItems should have size(1)
//        o.ingredients should have size 4
//        o.total should equal(34.0)
//      }))
//
//      val waiter = new Waiter(new Cook(new AssistantManager(cashier)))
//
//      val orderId = waiter.placeOrder(42, List(LineItem("Steak", 1)))
//      cashier.paid(orderId)
//    }

  }


  "A RestaurantOrder document" should {

    "have a default id" in {
      val order = new RestaurantOrder
      order.id.toString.count(_ == '-') should equal(4)
    }

    "read and write itself" in {
      val order = RestaurantOrder.fromJsonString(json1)
      val newJson = RestaurantOrder.asJsonString(order)
      newJson.stripMargin should equal(json1)
    }

    "access and mutate LineItem" in {
      val order = RestaurantOrder.fromJsonString(json1)
      order.lineItems should have size 1
      order.lineItems.head should equal(LineItem("Steak", 1))

      val newOrder = order.lineItems(List(LineItem("Pie", 1), LineItem("Guiness",1)))
      newOrder.lineItems should have size(2)
    }

    "mutate the table number" in {
      val order = RestaurantOrder.fromJsonString(json1)
      order.tableNumber should equal(1)
      val newOrder = order.tableNumber(2)
      newOrder.tableNumber should equal(2)
    }
  }

}
