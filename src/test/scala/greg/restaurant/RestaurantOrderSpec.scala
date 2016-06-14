package greg.restaurant

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
