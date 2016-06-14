package greg

import java.util.UUID

import play.api.libs.json._

import scala.collection.mutable
import scala.util.Try


case class LineItem(name: String, qty: Int)

trait HandleOrder {
  def handleOrder(order: RestaurantOrder)
}

class OrderPrinter extends HandleOrder {
  def handleOrder(order: RestaurantOrder): Unit =
    println(RestaurantOrder.asJsonString(order))
}

class Multiplexer(handleOrders: List[HandleOrder]) extends HandleOrder {
  def handleOrder(order: RestaurantOrder): Unit = handleOrders.foreach(_.handleOrder(order))
}

class RoundRobin(handleOrders: List[HandleOrder]) extends HandleOrder {
  val queue = mutable.Queue[HandleOrder]()
  queue ++= handleOrders

  def handleOrder(order: RestaurantOrder): Unit = {
    val h = queue.dequeue()
    try {
      h.handleOrder(order)
    } finally {
      queue.enqueue(h)
    }
  }
}

// aka "the publisher"
class Waiter(nextHandler: HandleOrder) {
  def placeOrder(tableNumber: Int, lineItems: List[LineItem]): UUID = {
    val newOrder = new RestaurantOrder().tableNumber(tableNumber).lineItems(lineItems)
    println(s"${this.getClass.getSimpleName}: Place order ${newOrder.id}")
    nextHandler.handleOrder(newOrder)
    newOrder.id
  }
}

// aka "the enricher"
class Cook(nextHandler: HandleOrder, name: String = "Unnamed") extends HandleOrder {

  val cookbook: Map[String, List[String]] = Map(
    "Steak" -> List("A really good piece of meat", "olive oil", "pepper", "salt")
  )

  def handleOrder(order: RestaurantOrder): Unit = {
    println(s"${this.getClass.getSimpleName}:$name: Starting cooking")
    Thread.sleep(2000)

    val ingredients: List[String] = order.lineItems.flatMap(li => cookbook.get(li.name) match {
      case None => throw new RuntimeException(s"Can not cook: ${li.name}")
      case Some(is) => is
    })

    nextHandler.handleOrder(order.ingredients(ingredients))
  }
}

// aka "the enricher"
class AssistantManager(nextHandler: HandleOrder) extends HandleOrder {

  val priceList: Map[String, Int] = Map("Steak" -> 34)

  def handleOrder(order: RestaurantOrder): Unit = {
    println(s"${this.getClass.getSimpleName}: Calculate ${order.id}")
    Thread.sleep(1000)
    val total = order.lineItems.foldLeft(0)((t, li) => t + (priceList.get(li.name) match {
      case None => throw new RuntimeException(s"Can not price item: ${li.name}")
      case Some(x) => x
    }) * li.qty)
    val taxes = total * 0.14
    nextHandler.handleOrder(order.total(total).tax(taxes))
  }
}

// aka "the controller"
class Cashier(nextHandler: HandleOrder) extends HandleOrder {

  var pendingOrders: Map[UUID, RestaurantOrder] = Map()

  def handleOrder(order: RestaurantOrder): Unit = {
    println(s"${this.getClass.getSimpleName}: Ready for payment ${order.id}")
    pendingOrders = pendingOrders + (order.id -> order)
  }

  def paid(orderId: UUID) = {
    pendingOrders.get(orderId).fold(println(s"Already paid: $orderId")){ order =>
      println(s"${this.getClass.getSimpleName}: Paying $orderId")
      pendingOrders = pendingOrders - orderId
      nextHandler.handleOrder(order.paid(true))
    }
  }
}

class RestaurantOrder(val json: JsObject = JsObject(List("id" -> JsString(UUID.randomUUID().toString)))) {

  implicit val lineItemFormat = Json.format[LineItem]

  def tableNumber: Int = (json \ "tableNumber").as[Int]
  def tableNumber(t: Int): RestaurantOrder = {
    val newJson = json ++ JsObject(List("tableNumber" -> JsNumber(t)))
    RestaurantOrder.fromJsonString(Json.prettyPrint(newJson))
  }
  def total: Double = (json \ "total").as[Double]
  def total(t: Double): RestaurantOrder = {
    val newJson = json ++ JsObject(List("total" -> JsNumber(t)))
    RestaurantOrder.fromJsonString(Json.prettyPrint(newJson))
  }
  def tax: Double = (json \ "tax").as[Double]
  def tax(t: Double): RestaurantOrder = {
    val newJson = json ++ JsObject(List("tax" -> JsNumber(t)))
    RestaurantOrder.fromJsonString(Json.prettyPrint(newJson))
  }
  def paid: Boolean = (json \ "paid").as[Boolean]
  def paid(t: Boolean): RestaurantOrder = {
    val newJson = json ++ JsObject(List("paid" -> JsBoolean(t)))
    RestaurantOrder.fromJsonString(Json.prettyPrint(newJson))
  }

  def lineItems: List[LineItem] = (json \ "lineItems").as[List[LineItem]]
  def lineItems(items: List[LineItem]): RestaurantOrder = {
    val newJson = json ++ JsObject(List("lineItems" -> Json.toJson(items)))
    RestaurantOrder.fromJsonString(Json.prettyPrint(newJson))
  }

  def ingredients: List[String] = (json \ "ingredients").as[List[String]]
  def ingredients(items: List[String]): RestaurantOrder = {
    val newJson = json ++ JsObject(List("ingredients" -> Json.toJson(items)))
    RestaurantOrder.fromJsonString(Json.prettyPrint(newJson))
  }

  def id: UUID = UUID.fromString((json \ "id").as[String])

}

object RestaurantOrder {
  def fromJsonString(jsonString: String) = new RestaurantOrder(Json.parse(jsonString).as[JsObject])
  def asJsonString(order: RestaurantOrder) = Json.prettyPrint(order.json)
}
