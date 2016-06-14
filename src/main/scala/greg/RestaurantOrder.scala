package greg

import java.util.UUID

import play.api.libs.json._

import scala.collection.mutable
import scala.util.Try

sealed trait Topic
object OrderPlaced extends Topic
object FoodCooked extends Topic
object OrderPriced extends Topic
object OrderPaid extends Topic

case class LineItem(name: String, qty: Int)

trait HandleOrder {
  def handleOrder(order: RestaurantOrder)
}

trait Startable {
  def start
}

trait HaveTTL {
  def ttl: Long
}

trait CanPublish {
  def publish(name: String, order: RestaurantOrder)
}

trait CanSubscribe {
  def subscribe(name: String, handler: HandleOrder)
}

class TopicBasedPubSub extends CanPublish with CanSubscribe{
  var subscribers: Map[String, List[HandleOrder]] = Map()

  def publish(name: String, order: RestaurantOrder): Unit = {
    subscribers.getOrElse(name,Nil).foreach(_.handleOrder(order))
  }

  def subscribe(name: String, handler: HandleOrder): Unit =
    subscribers.synchronized {
      subscribers = subscribers + (name -> (handler :: subscribers.getOrElse(name, Nil)))
    }
}

class OrderPrinter extends HandleOrder {
  def handleOrder(order: RestaurantOrder): Unit =
    println(RestaurantOrder.asJsonString(order))
}

class CountingHandler(name: String) extends HandleOrder {

  var count = 0

  def handleOrder(order: RestaurantOrder): Unit = {
    count = count + 1
    println(s"Counting $name: $count")
  }
}

class NullHandler extends HandleOrder {
  def handleOrder(order: RestaurantOrder): Unit = {}
}

class TTLHandler(handler: HandleOrder) extends HandleOrder {
  def handleOrder(order: RestaurantOrder): Unit = order match {
    case o: HaveTTL if o.ttl > System.currentTimeMillis() =>
      handler.handleOrder(o)
    case o: HaveTTL =>
      println(s"Dropping order ${o.id}")
    case o: RestaurantOrder =>
      handler.handleOrder(o)
  }
}


class ThreadedHandler(handler: HandleOrder, val name: String = "n/a") extends HandleOrder with Startable {
//  val mailbox = mutable.Queue[RestaurantOrder]()
  val mailbox = new java.util.concurrent.ConcurrentLinkedQueue[RestaurantOrder]

  def count = mailbox.size

  def start: Unit = {
    new Thread(new Runnable {
      def run(): Unit = {
        while(true) {
          if(mailbox.isEmpty) Thread.sleep(1000) else {
            val order = mailbox.poll()
//            println(s"$name dequeued ${order.id}, mailbox=${mailbox}")
            try {
              handler.handleOrder(order)
            } catch {
              case e: Throwable => println(s"Order $order failed with Throwable $e")
            }
          }
        }
      }
    }).start()
  }


  def handleOrder(order: RestaurantOrder): Unit = {
//    println(s"$name queue, mailbox=$mailbox")
    mailbox.offer(order)
  }
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

class MFDispatcher(handleOrders: List[ThreadedHandler]) extends HandleOrder {

  def handleOrder(order: RestaurantOrder): Unit =
    handleOrders.find(th => th.count < 5).fold{
      Thread.sleep(100)
      handleOrder(order)
    }(th => th.handleOrder(order))
}


// aka "the publisher"
class Waiter(publisher: CanPublish) {
  def placeOrder(tableNumber: Int, lineItems: List[LineItem]): UUID = {
    val newOrder = new RestaurantOrder().tableNumber(tableNumber).lineItems(lineItems)
    println(s"${this.getClass.getSimpleName}: Place order ${newOrder.id}")
    publisher.publish(OrderPlaced.toString, newOrder)
    newOrder.id
  }
}

// aka "the enricher"
class Cook(publisher: CanPublish, cookingTimeInMillis: Long = 1000, name: String = "Unnamed") extends HandleOrder {

  val cookbook: Map[String, List[String]] = Map(
    "Steak" -> List("A really good piece of meat", "olive oil", "pepper", "salt")
  )

  def handleOrder(order: RestaurantOrder): Unit = {
    println(s"${this.getClass.getSimpleName}:$name: Starting cooking ${order.id}")
    Thread.sleep(cookingTimeInMillis)

    val ingredients: List[String] = order.lineItems.flatMap(li => cookbook.get(li.name) match {
      case None => throw new RuntimeException(s"Can not cook: ${li.name}")
      case Some(is) => is
    })
    publisher.publish(FoodCooked.toString, order.ingredients(ingredients))
  }
}

// aka "the enricher"
class AssistantManager(bus: CanPublish) extends HandleOrder {

  val priceList: Map[String, Int] = Map("Steak" -> 34)

  def handleOrder(order: RestaurantOrder): Unit = {
    println(s"${this.getClass.getSimpleName}: Calculate ${order.id}")
    Thread.sleep(500)
    val total = order.lineItems.foldLeft(0)((t, li) => t + (priceList.get(li.name) match {
      case None => throw new RuntimeException(s"Can not price item: ${li.name}")
      case Some(x) => x
    }) * li.qty)
    val taxes = total * 0.14
    bus.publish(OrderPriced.toString, order.total(total).tax(taxes))
  }
}

// aka "the controller"
class Cashier(publisher: CanPublish) extends HandleOrder {

  var pendingOrders: Map[UUID, RestaurantOrder] = Map()

  def ordersToPay = pendingOrders.keys.toList

  def handleOrder(order: RestaurantOrder): Unit = {
    println(s"${this.getClass.getSimpleName}: Ready for payment ${order.id}")
    pendingOrders = pendingOrders + (order.id -> order)
  }

  def paid(orderId: UUID): Boolean = {
    if(pendingOrders.contains(orderId)) {
      val order = pendingOrders(orderId)
      println(s"${this.getClass.getSimpleName}: Paying $orderId")
      pendingOrders = pendingOrders - orderId
      publisher.publish(OrderPaid.toString, order.paid(true))
      true
    } else {
      false
    }
  }
}

class RestaurantOrder(val json: JsObject = JsObject(List("id" -> JsString(UUID.randomUUID().toString))), val ttl: Long = System.currentTimeMillis() + 3000) extends HaveTTL {

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
